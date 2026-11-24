# Scientific Lens Harness (Desktop, Java)

This is the JavaFX desktop version of the Python `sci-harness` project. It turns an idea, research question, or hypothesis into a testable hypothesis, a critique, and an experiment plan. Choose an installed agent CLI, an embedded GGUF model using llama.cpp over JNI, or the deterministic Mock engine. Local inference needs no external server or network after the model is downloaded; CLI inference uses the selected provider.

## Requirements

- JDK 25 (Temurin was used for testing)
- About 3 GB of free RAM for the 3B model, or about 6 GB for the 7B model

## Running

```powershell
.\mvnw.cmd javafx:run                                   # dev run
.\mvnw.cmd package                                      # tests + fat jar
java --enable-native-access=ALL-UNNAMED -jar target\scientific-lens-harness-0.1.0-all.jar
```

You can also double-click run.cmd to open the packaged app (it builds the jar if missing).

The desktop app opens with the Nobellist web edition's illustrated council. Pick a
title language, then click the illustration or press Enter or Space to open the
question composer. **모션 줄이기** skips the artwork motion and the transition.
The language selector changes the opening title and prompt. Home returns to the
composer with the current draft and result.

## Workflow Bench

- Enter a question in the top toolbar, choose **빠름 / 연구 / 전체**, then click **분석 실행** or press **Ctrl+Enter**.
- Open **설정** in the header for CLI provider/model, local GGUF selection/download, Mock, optional domain hint and examples. A detected CLI executable still requires login in that CLI.
- The four workflow buttons open the summary, lens analyses, debate/red-team graph and experiment plan.
- Completed analysis opens a focused report reader with the full hypothesis, next decision, first experiment, required data, predeclared success/failure criteria, weakest assumption, every red-team critique and market strategy. **분석 정보 · 출처** expands the provenance. Long content wraps and scrolls without truncation.
- Use **PDF 내보내기 → PDF 보고서 저장…** (or **Ctrl+P**) for a searchable A4 PDF with embedded Korean fonts, bookmarks and page numbers. It includes the complete report, experiment plan, lens analyses and debate. Generation runs in the background and shows the saved file path with a **PDF 열기** action. Markdown/JSON exports remain in the same menu.
- **분석 설정** restores the editing toolbar; **집중 읽기** hides it again. **Ctrl+Shift+R** toggles this view. **상세 보기**, **실시간 로그**, engine settings and export stay reachable while reading.
- The corner handle resizes the window. Results stack below 1200px; the minimum desktop size is 1000×700. Use the expand icon to edit long or multiline questions.
- Open **보관함** (or **Ctrl+L**) to search and reopen completed studies. Successful, uncancelled analyses save automatically under `%USERPROFILE%\.scientific-lens-harness\library`. A write failure leaves the finished result/export available and offers **다시 저장**. Saved reports restore their complete question, mode, domain, results and public events; reopening does not run the model again.
- In the library, edit the title and your own notes explicitly, import an existing analysis JSON, or **숨기기 / 복원**. Notes stay on this device and are excluded from model prompts and default AI report exports. Unsaved metadata changes remain visible and must be saved or reverted before switching/closing. Unreadable owned files are reported and retained. JSON files are limited to 16 MiB; imported bundles receive a new ID.
- **실시간 로그** now shows readable public task/answer cards, the actual current phase, elapsed time, auto-follow, and an optional raw-output view. A reopened report is explicitly labeled as saved history; raw subprocess output is not fabricated or stored in the library.

For a local model, open **설정 → 로컬 LLM**, then download a recommended model or choose an existing GGUF file. Models are saved to %USERPROFILE%\.scientific-lens-harness\models. Model and CLI settings remain in the existing user settings directory.

| Preset | Size | License | Notes |
|---|---|---|---|
| Qwen2.5 3B Instruct Q4_K_M | 2.1 GB | Qwen Research (non-commercial) | Default, faster |
| Qwen2.5 7B Instruct Q4_K_M | 4.7 GB | Apache-2.0 | Higher quality, commercial use allowed |

## Structure

```
domain/   immutable records with validation (port of the pydantic schemas)
core/     LensRegistry, Router, Orchestrator, Reporting, PdfReportWriter, Grader
runtime/  HarnessRuntime interface, MockRuntime (deterministic)
llm/      LlamaEngine (llama.cpp), LocalLlmRuntime, Prompts/JSON schemas, ModelDownloader
ui/       MainApp (JavaFX)
```

The CLI and embedded LLM runtimes use an actual coordinator loop. The coordinator reads the complete 15-lens catalog, selects agents and individual tasks, and chooses the next permitted typed action from the actual current results. Each agent is a separate model request with its own role context. For a debate, the chosen source agent receives the target's actual analysis and produces a critique; the target then receives that exact critique and returns a response plus its full revised analysis. Revised analyses and the public exchange feed the red-team review, experiment design and final decision. The same configured model runs these roles; a dialogue is never generated in place of those separate calls.

| Mode | Selected agents | Completed critique/reply exchanges | Manager-turn cap |
|---|---:|---:|---:|
| Fast | 5 | 1–2 | 8 |
| Research | 5–7 | 2–3 | 9 |
| Council | 15 | 3–5 | 11 |

The host validates agent IDs, counts, ordered debate pairs and required stage prerequisites before dispatch. A second invalid coordinator choice or an exhausted turn budget stops visibly. Each generated object has up to three schema/domain validation attempts; provider failures propagate without switching to Mock. Local decoding uses JSON grammar constraints; CLI output is strictly validated after generation. Cancellation also stops a silent CLI subprocess while its input/output is blocked. Calls execute sequentially so the embedded single-model runtime remains usable.

Codex calls use a neutral temporary working directory and ephemeral sessions. Fast applies `low` reasoning effort; Research/Council apply `medium` for that invocation only, avoiding inherited `ultra` settings that can exceed the eight-minute call timeout. The selected model and existing OAuth login are retained; the user's global CLI configuration is not changed.

Open **반론 · 레드팀 → 에이전트 호출·토론 기록** for the complete public tasks, replies and revisions. These records also appear in Markdown, JSON and PDF. They contain public outputs, not hidden reasoning. Mock retains its deterministic evaluation pipeline and does not claim actual agent debate.

New real debates also store `lens_revisions`: the exact finding, target response, current target analysis before the reply and the accepted full analysis afterward. The critique page places the actual reply beside the finding and expands every changed field with its complete before/after values. Markdown and PDF include the same comparison; JSON retains all fields, including unchanged fields. Changed confidence is still a model self-assessment. Old reports lacking these snapshots remain readable and explicitly disclose that no typed comparison was saved.

## Tests

```powershell
.\mvnw.cmd test                                          # unit tests + evals/cases.jsonl (mock)
.\mvnw.cmd test -Dtest=CouncilOpeningUiTest -Dslh.opening.ui=true # native opening, entry and Mock result captures
.\mvnw.cmd test -Dtest=UiSmokeTest -Dslh.ui.test=true       # opt-in native UI scenarios + PNG captures
.\mvnw.cmd test -Dtest=ProductUiTest -Dslh.product.ui=true # native library/restart/import/revisions/activity/failed-save/cancel QA
.\mvnw.cmd test -Dtest=CouncilUiLiveTest -Dslh.council.live=true # actual Codex gpt-6.1-sol UI/debate/PDF; requires CLI login
.\mvnw.cmd test -Dtest=CouncilUiLiveTest -Dslh.council.live=true -Dslh.council.replay=target/council-qa/result.json # native replay/export of saved real output; no new model calls
.\mvnw.cmd test -Dtest=LocalLlmSmokeTest -Dslh.model=C:\path\model.gguf   # real inference (a few minutes)
```

## Performance (i7-12700H, CPU only, 3B Q4_K_M)

- About 12–17 tokens/s
- Per-lens stage: about 46 seconds (Korean output is token-heavy)
Those per-lens measurements predate coordinator-driven debate. Overall time now also includes manager choices, separate critique/reply calls and complete state prompts; it depends on mode, model, context size, output length and CPU load. Larger council states may require a higher-capacity GGUF model and context setting.
The **CLI engine** (Kiro or GPT) is another option; runtime depends on the selected provider and model.

The bundled Windows native library is CPU-only. For GPU acceleration you need a CUDA build of `jllama.dll`; see the java-llama.cpp documentation.

## PDF portability

PDF export is implemented directly in Java with Apache PDFBox. No browser, Python or external converter is required. Noto Sans KR Regular/Bold are bundled for native UI and PDF text; their SIL Open Font License is included under src/main/resources/fonts/OFL.txt. Unsupported glyphs are displayed as explicit Unicode codes rather than silently discarded.

## Principles

The scientist names are methodological lenses only. The app does not reproduce any real person's thoughts or claim historical facts. The output is an experiment plan, not a proof.

### 인터랙티브 연구 스튜디오

앱을 열면 노벨리스트 웹판의 의회 오프닝이 나옵니다. 제목 언어를 고르고 화면을 누르거나
Enter 또는 Space를 누르면 질문 작성 화면으로 들어갑니다. 질문을 직접 쓰거나 예시를 눌러 입력하고,
빠르게 탐색·깊이 있는 연구·전체 렌즈 중 하나를 선택해 연구를 시작하세요.
웰컴과 작업 공간은 같은 초안을 공유합니다. 홈으로 이동해도 결과는 남아 있고
`이전 분석으로 돌아가기`로 다시 열 수 있습니다.

CLI/로컬 LLM 분석 중에는 토론 무대가 실제 선택된 렌즈, 현재 호출과 수신한
공개 답변을 시각화합니다. 렌즈를 선택하면 방법론과 공개 기록 전문을 읽습니다.
`현재 작업`은 진행 중인 노드를 다시 따라갑니다. `전체 기록`과 `원본 출력 보기`로
기존 기록도 확인할 수 있습니다. Mock은 템플릿 테스트로 표시하며 실제 호출을
꾸며내지 않습니다. 저장된 보고서의 토론 무대는 저장 기록임을 표시합니다.

헤더의 `모션`을 선택하면 움직임을 줄입니다. 분석 중 설정 영역은 접히고,
취소와 기록 접근은 유지됩니다. 완료 후에는 기존 보고서·PDF 내보내기·보관함을
그대로 사용할 수 있습니다.
