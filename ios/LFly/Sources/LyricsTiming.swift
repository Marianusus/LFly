import Foundation

/// Word-level timing for karaoke highlighting: the phone's LyricsTiming.kt, line by line. Lines
/// with no real word timings get them estimated by spreading the line across its words, weighted
/// by syllables, capped to a plausible singing time so an instrumental gap never stretches it.
enum LyricsTiming {
    struct Word: Equatable {
        let text: String
        let startMs: Int64
        let endMs: Int64
    }

    private static let cjk = try! NSRegularExpression(pattern: "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af]")
    private static let vowels = Set("aeiouyàáâãäåāăąèéêëēĕėęěìíîïĩīĭįıòóôõöøōŏőùúûüũūŭůűųýÿæœаеёиоуыэюяіїє")

    private static func isCJK(_ s: String) -> Bool {
        cjk.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// The words of a line; scripts without spaces (CJK) give one token per character.
    static func splitWords(_ text: String) -> [String] {
        var out: [String] = []
        for token in text.split(whereSeparator: \.isWhitespace).map(String.init) where !token.isEmpty {
            guard isCJK(token) else { out.append(token); continue }
            var buffer = ""
            for ch in token {
                if isCJK(String(ch)) {
                    if !buffer.isEmpty { out.append(buffer); buffer = "" }
                    out.append(String(ch))
                } else {
                    buffer.append(ch)
                }
            }
            if !buffer.isEmpty { out.append(buffer) }
        }
        return out
    }

    static func syllableCount(_ raw: String) -> Int {
        let word = raw.lowercased().filter(\.isLetter)
        if word.isEmpty { return 1 }
        if isCJK(word) { return max(word.count, 1) }
        var count = 0
        var inVowels = false
        for ch in word {
            let v = vowels.contains(ch)
            if v && !inVowels { count += 1 }
            inVowels = v
        }
        return max(count, 1)
    }

    static func estimate(_ text: String, start: Int64, end: Int64, minWordMs: Int64 = 170) -> [Word] {
        let words = splitWords(text)
        guard !words.isEmpty else { return [] }
        let syllables = words.reduce(0) { $0 + syllableCount($1) }
        let speechCap = min(max(Int64(syllables) * 500, 1_500), 6_000)
        let window = max(min(end - start, speechCap), Int64(words.count) * minWordMs)
        let weights = words.map { max(Double(syllableCount($0)) * 0.7 + Double(min($0.count, 12)) * 0.3, 0.5) }
        let total = weights.reduce(0, +)
        var cumulative = 0.0
        return words.enumerated().map { i, w in
            let a = cumulative / total
            cumulative += weights[i]
            let b = cumulative / total
            let s = start + Int64(Double(window) * a)
            let e = start + Int64(Double(window) * b)
            return Word(text: w, startMs: s, endMs: max(e, s + 1))
        }
    }

    /// The line's words: the source's own timings, else estimated up to the next line.
    static func words(for line: LyricLine, nextStart: Int64?) -> [Word] {
        if !line.words.isEmpty { return line.words.map { Word(text: $0.text.trimmingCharacters(in: .whitespaces), startMs: $0.startMs, endMs: $0.endMs) } }
        if line.text.trimmingCharacters(in: .whitespaces).isEmpty { return [] }
        let end = nextStart.flatMap { $0 > line.timeMs ? $0 : nil } ?? line.timeMs + 4_000
        return estimate(line.text, start: line.timeMs, end: end)
    }

    /// The last word that has started at [position], or -1.
    static func activeWord(_ words: [Word], at position: Int64) -> Int {
        var lo = 0, hi = words.count - 1, result = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if words[mid].startMs <= position { result = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return result
    }

    /// The line playing at [position]: the last one that has started.
    static func activeLine(_ lines: [LyricLine], at position: Int64) -> Int {
        lines.lastIndex { $0.timeMs <= position } ?? -1
    }
}
