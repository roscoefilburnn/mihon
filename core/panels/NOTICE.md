# Third-party notices for `:core:panels`

## Ported from batunii/chika

The files under `src/main/kotlin/mihon/core/panels/chika/` (`Panel.kt`, `PanelPlanner.kt`,
`PanelOrdering.kt`, `PanelGapFiller.kt`, `PanelPipeline.kt`, `PanelReliability.kt`,
`YoloPanelDecoder.kt`) are Modifications, within the meaning of the Mozilla Public License v2.0,
of the corresponding files in [batunii/chika](https://github.com/batunii/chika)
(`shared/src/commonMain/kotlin/com/chakra/comicreader/detection/`). They remain licensed under
MPL 2.0 — see `/LICENSE-MPL-2.0.txt` at the repository root — and are combined with this
project's Apache-2.0 code as a Larger Work per MPL 2.0 §3.3. The unmodified original source is
available from the upstream repository linked above; this fork's own copies (with the
modifications noted in each file) are available in this repository's history.

## Bundled model weights: chika's own model, AGPL-3.0

`assets/models/panel_detector.tflite` is chika's own trained model, copied unmodified from
`app/src/main/assets/manga_panel_detector_int8.tflite` in batunii/chika. That file is exported
via Ultralytics' tooling from a YOLO26n model, and its own embedded metadata (`metadata.json`
inside the `.tflite` archive, viewable by unzipping the file) declares:

```
"author": "Ultralytics",
"license": "AGPL-3.0 License (https://ultralytics.com/license)"
```

That is a separate license from chika's own MPL-2.0 repository license. AGPL-3.0's copyleft and
network-use provisions are in real tension with distributing this file as part of an Apache-2.0
app — **this asset is included on the basis that this build of Mihon is for personal use and is
not being distributed.** If that changes (a release build, a public fork, an APK handed to
anyone else), this file needs to be pulled or replaced with a model under compatible licensing
first; see the AGPL-3.0 text at https://www.gnu.org/licenses/agpl-3.0.txt and the Ultralytics
license terms at https://ultralytics.com/license.

The model itself was trained on Manga109-s (the subset of the Manga109 dataset whose
contributing authors specifically cleared commercial use of ML results); that clearance concerns
the *training data* and is separate from the *Ultralytics export tooling's own* AGPL-3.0 license
notice on the resulting weights file above.

`TfliteChikaDetector` loads this asset at runtime and decodes its output with the ported
`YoloPanelDecoder`; if the asset is ever removed, it falls back to reporting
`DetectionResult.Inconclusive` so the pipeline degrades to the classical detector / no panels
for that page rather than failing.

## Ported from ACBF Editor 3.0 — GPLv3, not a "Larger Work" combination

`src/main/kotlin/mihon/core/panels/acbfeditor/AcbfEditorFrameDetector.kt` is a Modification of
ACBF Editor 3.0's `src/frames_editor.py` (`FramesEditorDialog.frames_detection()` and its
`centroid_for_polygon()`/`area_for_polygon()`/`round_to()` helpers), supplied directly by the
project owner as the `ACBFEditor3.0_linux.tar.gz` source distribution (Launchpad, where the
project is hosted, is blocked by this environment's network egress policy, so this file was
provided out-of-band rather than fetched).

Original work Copyright (C) 2011-2024 Robert Kubik
(https://github.com/ACBF-Advanced-Comic-Book-Format), licensed under the **GNU General Public
License, version 3** (full text at `/LICENSE-GPL-3.0.txt` at the repository root) — a different
and stricter license than chika's MPL 2.0. GPLv3 does not have MPL's file-level "Larger Work"
carve-out: incorporating GPLv3-covered code into an Apache-2.0 application and distributing the
result is a real copyleft conflict, not just an attribution/notice requirement. **This file is
included on the basis that this build of Mihon is for personal use and is not being
distributed.** If that changes, this specific detector needs to be removed, replaced, or the
distribution's licensing reconsidered before shipping — the classical-detector fallback chain in
`AcbfPanelResolver` degrades gracefully (falls through to the ML detector / no panels) if it's
removed.

The port uses OpenCV for Android (`org.opencv:opencv`, Apache-2.0-licensed, no distribution
conflict of its own) to call the same primitives the original Python uses
(`cv2.bilateralFilter`, `cv2.Canny`, `cv2.morphologyEx`, `cv2.findContours`, `cv2.approxPolyDP`,
`cv2.moments`) rather than hand-reimplementing that image-processing pipeline, to stay faithful
to the original's actual behavior.
