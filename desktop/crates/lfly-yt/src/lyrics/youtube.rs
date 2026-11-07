//! YouTube Music's own lyrics for the playing video (port of the phone's
//! `SourceYouTube.music`): the `next` response's Lyrics tab, then its
//! `browse` page. Timed lines come from the `timedLyricsModel` (signed in, or
//! as the Android app); the web page's description shelf is the plain fallback.

use super::sheet::{Line, Sheet};
use crate::json::{self, Obj};
use crate::YtMusic;
use serde_json::Value;

/// browseId and params of the Lyrics tab of a `next` response.
pub(crate) fn lyrics_endpoint(next: &Value) -> Option<(String, Option<String>)> {
    let tabs = json::collect(next, "tabRenderer");
    let endpoint = |tab: &Obj| json::path(tab, &["endpoint", "browseEndpoint"]).cloned();
    let named = tabs.iter().find(|t| json::string(t, "title").is_some_and(|s| s.eq_ignore_ascii_case("lyrics"))).and_then(|t| endpoint(t));
    // Other languages name the tab differently: any tab whose page is a lyrics page.
    let found = named.or_else(|| {
        tabs.iter().filter_map(|t| endpoint(t)).find(|e| json::string(e, "browseId").is_some_and(|id| id.starts_with("MPLY")))
    })?;
    let id = json::string(&found, "browseId").filter(|s| !s.is_empty())?;
    Some((id, json::string(&found, "params").filter(|s| !s.is_empty())))
}

/// A line's text with its start and end, when YouTube has them.
pub(crate) type TimedLine = (String, Option<i64>, Option<i64>);

/// Lines of a `timedLyricsModel` (start/end when YouTube has times); `None` without one.
pub(crate) fn timed_lines(page: &Value) -> Option<Vec<TimedLine>> {
    let data = json::collect(page, "lyricsData").into_iter().find_map(|d| json::arr(d, "timedLyricsData"))?;
    let lines: Vec<_> = data
        .iter()
        .filter_map(|l| {
            let o = l.as_object()?;
            let text = o.get("lyricLine")?.as_str()?.trim().to_string();
            if text.is_empty() {
                return None;
            }
            let cue = json::obj(o, "cueRange");
            Some((text, cue.and_then(|c| json::long(c, "startTimeMilliseconds")), cue.and_then(|c| json::long(c, "endTimeMilliseconds"))))
        })
        .collect();
    (!lines.is_empty()).then_some(lines)
}

/// Timed lines into a synced sheet, or plain text when some line has no time.
pub(crate) fn timed_sheet(lines: Vec<TimedLine>) -> Sheet {
    if lines.iter().all(|l| l.1.is_some()) {
        let mut synced: Vec<Line> = lines.into_iter().map(|(t, s, e)| Line::new(s.unwrap_or(0), e, t)).collect();
        synced.sort_by_key(|l| l.start);
        Sheet::Synced(synced)
    } else {
        Sheet::Plain(lines.into_iter().map(|l| l.0).collect::<Vec<_>>().join("\n"))
    }
}

/// The web page's `musicDescriptionShelfRenderer` text.
pub(crate) fn description(page: &Value) -> Option<String> {
    let shelf = json::collect(page, "musicDescriptionShelfRenderer").into_iter().next()?;
    let text = json::runs_text(json::obj(shelf, "description")).trim().to_string();
    (!text.is_empty()).then_some(text)
}

/// The lyrics sheet of a video; `Ok(None)` when YouTube has none.
pub(crate) async fn fetch(yt: &YtMusic, video_id: &str) -> Result<Option<Sheet>, String> {
    let next = yt.next_json(video_id).await.map_err(|e| e.to_string())?;
    let Some((browse_id, params)) = lyrics_endpoint(&next) else { return Ok(None) };
    // Signed in, the web page itself may carry real-time lyrics.
    let mut web: Option<Value> = None;
    if yt.account().is_some() {
        if let Ok(page) = yt.browse_json(&browse_id, params.as_deref(), None).await {
            if let Some(lines) = timed_lines(&page) {
                return Ok(Some(timed_sheet(lines)));
            }
            web = Some(page);
        }
    }
    if let Some(page) = yt.browse_android_music(&browse_id, params.as_deref()).await {
        if let Some(lines) = timed_lines(&page) {
            return Ok(Some(timed_sheet(lines)));
        }
    }
    let page = match web {
        Some(p) => p,
        None => yt.browse_json(&browse_id, params.as_deref(), None).await.map_err(|e| e.to_string())?,
    };
    Ok(description(&page).map(Sheet::Plain))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn finds_the_lyrics_tab() {
        let next = json!({"contents": {"tabs": [
            {"tabRenderer": {"title": "Up next"}},
            {"tabRenderer": {"title": "Lyrics", "endpoint": {"browseEndpoint": {"browseId": "MPLYt_abc", "params": "xyz"}}}},
            {"tabRenderer": {"title": "Related", "endpoint": {"browseEndpoint": {"browseId": "MPTRt_abc"}}}}]}});
        assert_eq!(lyrics_endpoint(&next), Some(("MPLYt_abc".into(), Some("xyz".into()))));
        let other = json!({"tabs": [{"tabRenderer": {"title": "Testo", "endpoint": {"browseEndpoint": {"browseId": "MPLYt_x"}}}},
            {"tabRenderer": {"title": "Related", "endpoint": {"browseEndpoint": {"browseId": "MPTRt_abc"}}}}]});
        assert_eq!(lyrics_endpoint(&other).unwrap().0, "MPLYt_x");
        let none = json!({"tabs": [{"tabRenderer": {"title": "Related", "endpoint": {"browseEndpoint": {"browseId": "MPTRt_abc"}}}}]});
        assert_eq!(lyrics_endpoint(&none), None);
    }

    #[test]
    fn timed_lyrics_model() {
        let page = json!({"contents": {"elementRenderer": {"newElement": {"type": {"componentType": {"model": {"timedLyricsModel": {"lyricsData": {"timedLyricsData": [
            {"lyricLine": "Like the legend of the phoenix", "cueRange": {"startTimeMilliseconds": "12000", "endTimeMilliseconds": "15500"}},
            {"lyricLine": " "},
            {"lyricLine": "Our ends were beginnings", "cueRange": {"startTimeMilliseconds": "15500", "endTimeMilliseconds": "18000"}}]}}}}}}}}});
        let lines = timed_lines(&page).unwrap();
        let Sheet::Synced(synced) = timed_sheet(lines) else { panic!() };
        assert_eq!((synced[1].start, synced[1].end, synced[1].text.as_str()), (15_500, Some(18_000), "Our ends were beginnings"));
        let untimed = json!({"lyricsData": {"timedLyricsData": [{"lyricLine": "a"}, {"lyricLine": "b"}]}});
        assert_eq!(timed_sheet(timed_lines(&untimed).unwrap()), Sheet::Plain("a\nb".into()));
        let web = json!({"contents": {"musicDescriptionShelfRenderer": {"description": {"runs": [{"text": "line one\nline two"}]}}}});
        assert_eq!(description(&web).as_deref(), Some("line one\nline two"));
        assert!(timed_lines(&web).is_none());
    }
}
