# Kloc

A very, very basic multithreaded line counter written in Kotlin/Native.
It skips files larger than 20 MB and tries to guess if a file's generated.
It doesn't skip comments.
It can roughly match [scc]'s and [tokei]'s performance: 250ms to count the Godot repository on my computer (albeit with
a much more limited feature set).
It's not designed as a real tool, mostly just an experiment testing whether Kotlin/Native can reach native code level
performance.
