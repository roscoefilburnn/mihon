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

## Not included: chika's bundled model weights

This module deliberately does **not** bundle chika's own trained model,
`app/src/main/assets/manga_panel_detector_int8.tflite`. That file is exported via Ultralytics'
tooling from a YOLO26n model, and its own embedded metadata (`metadata.json` inside the `.tflite`
archive) declares:

```
"author": "Ultralytics",
"license": "AGPL-3.0 License (https://ultralytics.com/license)"
```

That is a separate license from chika's own MPL-2.0 repository license, and AGPL-3.0's terms are
in real tension with distributing it as part of an Apache-2.0-licensed app. `TfliteChikaDetector`
in this module expects a model asset at `assets/models/panel_detector.tflite` but does not ship
one — until a model with compatible licensing is supplied there, ML-based panel detection always
reports `DetectionResult.Inconclusive` and the pipeline falls back to the classical detector /
no panels for that page.

The model referenced above was trained on Manga109-s (the subset of the Manga109 dataset whose
contributing authors specifically cleared commercial use of ML results); that clearance concerns
the *training data*, and is separate from the *Ultralytics export tooling's own* AGPL-3.0 license
notice on the resulting weights file above.

## Not ported: ACBF Editor

`WhitespaceGutterPanelDetector.kt` is an original implementation of the general class of
technique (whitespace/gutter projection-profile segmentation) that ACBF Editor's own frame
auto-detection is documented to use — not a port of its actual source. ACBF Editor's source is
hosted on Launchpad (`lp:acbf`), which this environment's network policy does not allow
reaching; its actual algorithm was not available to copy from.
