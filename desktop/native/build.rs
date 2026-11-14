//! macOS only: ONNX Runtime's CoreML code (the beat model, through `ort`) checks the macOS
//! version with `@available`, which needs `___isPlatformVersionAtLeast` from clang's runtime
//! library. rustc links with `-nodefaultlibs`, so that library is added here by hand.

fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("macos") {
        return;
    }
    let dir = std::process::Command::new("clang")
        .arg("--print-runtime-dir")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string());
    match dir {
        Some(dir) if !dir.is_empty() => {
            println!("cargo:rustc-link-search=native={dir}");
            println!("cargo:rustc-link-lib=static=clang_rt.osx");
        }
        _ => println!("cargo:warning=clang --print-runtime-dir failed: ___isPlatformVersionAtLeast may be missing"),
    }
}
