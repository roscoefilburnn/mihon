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

## Not ported: ACBF Editor

`WhitespaceGutterPanelDetector.kt` is an original implementation of the general class of
technique (whitespace/gutter projection-profile segmentation) that ACBF Editor's own frame
auto-detection is documented to use — not a port of its actual source. ACBF Editor's source is
hosted on Launchpad (`lp:acbf`), which this environment's network policy does not allow
reaching; its actual algorithm was not available to copy from.
