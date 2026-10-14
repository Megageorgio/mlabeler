# SingForge — design

A desktop trainer for singing voice models: DiffSinger voicebanks first, then vocoders, RVC, a multilingual TTS and
the labelling models (TIFA, WFL-ASR, MCFL), plus G2P and note extraction. Windows, macOS and Linux; desktop only.

Everything Python (training, models, data tools) runs in mVocalToolkit engines. SingForge is the window over them,
built on mLabeler's code and following its design rules (`docs/DESIGN.md`).

Background research: `mVocalToolkit/docs/notes/diffsinger-trainer.md` (complaints about existing tools),
`difftrainer-0.4.10-analysis.md`, `phoneme-sets.md`.

## Goals, in order

1. **DiffSinger training that just works**: no conda, no CUDA toolkit, no console, no YAML. From recordings (or a
   labelled folder) to a checked OpenUtau voicebank. Everything DiffTrainer does, done better.
2. **Vocoder fine-tuning** for a voicebank (PC-NSF-HiFiGAN).
3. **Dictionary, phonemes and G2P tools**: import someone else's dictionary, remap its phonemes, merge languages,
   train a G2P for a new language.
4. **RVC** (voice conversion) training.
5. **TIFA, WFL-ASR, MCFL** training and fine-tuning on labels corrected in mLabeler.
6. **TTS** voice training (speaking voice of a singer), many languages including Russian.
7. **GAME** (notes) fine-tuning.

## Principles (mLabeler's, plus three of its own)

mLabeler's apply as they are: quiet by default, keyboard first, behaviour is visible, what you see is what is
written, big folders stay fast, themes as tokens, plain short texts (no exclamation marks, no emoji), English source
and Russian from the start. In addition:

1. **Nothing long is a blind wait.** Every running step shows what it does, progress, time left, and for training
   the losses, GPU memory and the latest test render.
2. **Check before spending hours.** Data, dictionary and config are checked before training starts; a 1–2 minute
   "check run" goes through every stage on a few files.
3. **Licenses are shown, never hidden.** Datasets, base models and vocoders show their license before use; a
   voicebank or model inherits the strictest one, and the export says so.

## Architecture

```
SingForge (Kotlin, Compose Desktop)          mVocalToolkit (Python, local or another PC)
  projects, wizard, graphs, players   ──HTTP/WS──►  jobs, datasets, models, engines
  shared with mLabeler: theme, i18n,                 training engines (own envs, pinned upstream):
  toolkit client, audio, dialogs                     diffsinger, rvc, tts, tifa_train, wfl_train,
                                                     mcfl_train, g2p_train, game_train
```

**Code location.** A new `:singforge` desktop app module in the mLabeler repository, next to `:app`. What both apps
use moves out of `:app` into a shared `:ui` module: theme and tokens, i18n machinery, the toolkit client and
manager (start, share, stop the local toolkit), dialogs, players, self-update. `:core` (formats, audio, checks)
is already shared. Separate release workflow and version for SingForge. "Open in mLabeler" passes a folder (and a
position) to mLabeler when it is installed.

**Toolkit side.** Training is a new kind of job:
- runs for hours, survives the SingForge window closing; its state is in `<home>/training/<run>/run.json`, and a
  restarted toolkit re-attaches to it;
- the engine worker starts the upstream trainer as a child process (own process group) and streams events: step,
  epoch, losses, learning rate, GPU memory, checkpoints, test renders, log lines; errors as text the window shows;
- methods: start, stop (graceful, then kill), resume, list checkpoints, render a checkpoint, export;
- one training at a time per GPU; labelling jobs can still run on the CPU.

Remote training works as labelling does today: `mvt serve --host 0.0.0.0` on the GPU machine, SingForge connects
with the token, data is uploaded once (or the folder is already there).

## Projects

A project is a folder: `singforge.json` (what, which data, which engine versions, settings), `data/` (links or
copies), `runs/<name>/` (configs, checkpoints, logs, renders), `export/`. A project can hold several models of one
voice (acoustic, variance, vocoder, RVC, TTS). Opening it a year later shows what was trained with what.

## Data

- **Sources**: a folder of recordings (any format), labelled folders (lab/TextGrid/csv/ds), DiffTrainer and Colab
  layouts (speaker folders, `name.lang` tags), toolkit datasets (`GET /datasets`, each with its license).
- **Preparation** through the toolkit (all already exist): separation and de-reverb on request, cutting at pauses,
  transcription, alignment (TIFA / SOFA / HubertFA) or segmentation (MCFL / WFL), refinement, notes (GAME), f0
  (RMVPE). New: automatic `ph_num` from the dictionary, `.ds` with notes and slurs.
- **Dataset doctor** (before every training): minutes per speaker and language, phoneme coverage of the dictionary
  (missing phonemes and which speakers have them), sample rate, clipping, level, narrow band (for vocoders), noise
  and reverb, name mismatches, too short or empty intervals, junk labels. Every problem links to the file and time:
  "Open in mLabeler".
- **Doubtful places** from the aligner's confidence, as a review list.
- **Conversion** to each trainer's format: DiffSinger csv/ds, TIFA `index.csv`, WFL/MCFL wav+lab folders, RVC wav
  folder, TTS clip list with text.

## Dictionaries, phonemes, G2P

- **IPA table** per language (native phoneme → IPA + voicing, aspiration, place) as the single source of truth.
  From it: strict DiffSinger `merged_phoneme_groups`, the looser MCFL/WFL merge map, the coverage report, mapping
  suggestions. Ships with tables for the MCFL multi13 languages.
- **Dictionary editor**: open a DiffSinger dictionary, OpenUtau `dsdict.yaml`, SOFA `dict.txt`, CMUdict or a word
  list; rename and split phonemes with a mapping table (1→many, many→1) pre-filled from IPA (panphon nearest
  neighbour); find words using a phoneme; diff two dictionaries.
- **Language merge wizard** for multilingual voicebanks: languages and speakers, suggested groups (only identical
  sounds), conflicts (same name, different sound: en r / ja r), the coverage of each target language per speaker.
- **G2P training** (`g2p_train` engine): trains the OpenUtau-style RNN-T the toolkit already runs for SOFA, from a
  `dict.txt`; exports `cfg.yaml + model.ptsd` and g2pflow ONNX (for TIFA). Minutes on a GPU. Optional: bootstrap a
  new language from CharsiuG2P or epitran rules.

## DiffSinger (first priority)

**Engine** `diffsinger`: openvpi/DiffSinger pinned (main at a tested commit, today `ba031f6`, or v2.5.1 if main
misbehaves); Python 3.11; `torch>=2.7,<2.9` (RTX 50 needs cu128), `lightning~=2.3.0`, `numpy<2`, `librosa<0.10`,
`setuptools<81`. No runtime patching: the worker writes configs and calls `scripts/binarize.py`, `train.py`,
`export.py`, `drop_spk.py`, `infer.py`; a tiny Lightning callback (added through a wrapper script, not by editing
upstream files) prints step, losses and validation results as JSON lines. Updating the pin is a separate step with a
test: tiny dataset → binarize → 200 steps → export → render.

**Wizard**: Voice → Data → Check → Languages → Model → Train → Listen → Export.
- **Model step** with presets instead of YAML: "little data / much data", GPU memory (8/12/16/24 GB), fast /
  quality; backbone (LYNXNet2 default), variance parameters with explanations (energy + breathiness + voicing never
  together; tension marked unstable), augmentation, multi-speaker settings. An "Advanced" table shows every config
  key with its schema description. DiffTrainer's tuned defaults are the starting values (RMVPE, f0_max 1600, L1,
  pitch shift ±3, tension logits ±8).
- **Training**: environment check (GPU, driver, memory) before starting; `probe_and_cap_max_frames: true`
  (out of memory shows in the first minutes); batch size from GPU memory; a fixed validation set (not random per
  write); queue acoustic → variance (duration with variance, pitch separately, as openvpi advises) → vocoder.
- **Fine-tuning by default** from a base model of the language when the catalog has one (`ds_base` models); bases
  are offered by phoneme coverage.
- **Preview tiers**: Check (1–2 min), Preview (acoustic only, speaker embedding + frozen base, ~5–15 min), Full
  (continues from the preview).
- **Listen**: every N steps the same test phrases (per language) are rendered; a checkpoint ladder with A/B
  listening and the loss graph under it; an overfitting hint when validation loss rises while training loss falls.
- **Export**: ONNX + OpenUtau folder (character.yaml/txt, dsconfig with every current key, dsdur/dspitch/dsvariance,
  languages.json, use_lang_id, speaker embeddings, dsdict per language, vocoder reference or own vocoder), donors
  dropped (`drop_spk`), a linter for all of it, a test render of the exported ONNX bank (the toolkit's ONNX
  renderer), the license of everything used. The bank report lists languages, speakers and parameters.

## Vocoder

**Engine** `vocoder_train`: openvpi/SingingVocoders (`4d0889c`), same environment pins as `diffsinger` (shared
uv cache). Starts from PC-NSF-HiFiGAN 2025.02 "for fine-tuning"; 44.1 kHz, mel settings fixed; ~2000 real steps;
the progress bar shows real steps (the trainer counts both optimisers); bf16 off; settings by GPU memory (16 GB:
crop 32, batch 10). Export through DiffSinger's `export.py nsf-hifigan` into the voicebank. Band check of the data
before starting. License: the base weights are CC BY-NC-SA 4.0 and so is the result; shown before training.
HiFiPLN as an optional second engine later (its own environment: numpy 2, lightning 2.5).

## RVC

**Engine** `rvc`: IAHispano/Applio pinned (`94f5d69`), only its `requirements-engine.txt` (no web UI), Python 3.11,
torch as Applio pins it. Steps through `core.py`: prerequisites, preprocess, extract (RMVPE, ContentVec), train
(HiFi-GAN vocoder by default), index. Per-epoch losses from its logs. Outputs `.pth` + `.index`. Data: the same dry
recordings, no labels. An overtraining hint like Applio's detector, test conversions of a fixed phrase per saved
epoch.

## TTS

**Engine** `tts`: **VoxCPM2** (OpenBMB, Apache-2.0 code and weights, 30 languages including Russian, official LoRA
and full fine-tuning, active). LoRA on 5–50 clips of a speaker; needs ~20 GB GPU memory for LoRA (to be measured;
gradient checkpointing may fit 16 GB). For smaller cards: **Qwen3-TTS 0.6B** (Apache-2.0, 10 languages including
Russian, official single-speaker fine-tuning). GPT-SoVITS is not offered (no Russian upstream). Data: clips with
text from the toolkit's transcription, optional Russian stress marks. It is "the speaking voice of your singer",
not singing.

## Labelling models

- `tifa_train`: openvpi/TIFA at the commit the `tifa` engine uses (`614a277`): `binarize.py`, `train.py`, the
  post-training stage, `reduce.py`, then packaging into the folder the `tifa` engine loads (model.pt, config.yaml,
  vocabulary.json, dictionaries). Small-data settings (validation split, augmentation off when there are no noise
  files). Fine-tuning through `training.finetuning.pretraining_from`.
- `wfl_train`: the recipes now in the unversioned `wfl_ru` folder (EMA, per-voice balancing, early stop by FA/CMAE,
  multilingual sampling) move into a versioned repository first (proposal: the MCFL repo, which already vendors the
  WFL model), then the engine pins it. Output: the folder `wfl_asr_v2` loads.
- `mcfl_train`: Megageorgio/MCFL pinned; needs two additions in the repo: the `mcfl-v1` export (now in
  `wfl_ru/student_multi/export_mlabeler.py`) and `--init` to fine-tune a student.
- The loop with mLabeler: labels corrected in mLabeler (marked done) are the fine-tuning data; the report compares
  the new model with the old on held-out files (PER, boundary error), and the new model goes into the toolkit with
  `models import`.
- mRefinerModel: the same pattern, later.

## Notes (GAME)

GAME replaces SOME (SOME's README points to GAME; GAME has languages, robustness to noise, word-aligned notes,
ONNX and a documented fine-tuning block). `game_train`: openvpi/GAME, fine-tuning from the released checkpoints on
the user's labelled notes; the small model by default (the medium one needs ~20 GB to train). Weights CC BY-NC-SA.

## Licenses

- Datasets: from the toolkit catalog, only those that allow training; the license is shown and accepted before the
  download and kept in the dataset folder (done in the toolkit).
- Base models, vocoders, GAME, TIFA bases: their license is shown when chosen.
- A trained model's license is the strictest of its inputs (data, base, vocoder); the export writes it into the
  voicebank (readme and character.txt) with the credit texts.

## Interface

```
┌ top bar: project ▾ · steps (Data · Check · Languages · Model · Train · Listen · Export) · toolkit ● ─┐
├ runs ┃ step workspace                                                           ┃ inspector      ┤
│      ┃  Data: sources, doctor results, coverage                                 ┃ selected item, │
│      ┃  Train: loss graphs, GPU, log, queue                                     ┃ settings with  │
│      ┃  Listen: checkpoint ladder, A/B, test phrases                            ┃ explanations   │
├──────┸──────────────────────────────────────────────────────────────────────────┸────────────────┤
└ status: running job · step · time left · GPU memory · problems                                   ┘
```

Same width classes and panel rules as mLabeler (desktop widths only). Keyboard: step switching, play/A-B in Listen,
start/stop. A system notification and the optional sound when a long job ends (as in mLabeler).

## Milestones

1. **Toolkit training runtime + `diffsinger` engine**, CLI `mvt train` on a tiny dataset (binarize → steps →
   export). Proves install without conda on Windows, Linux, macOS (CPU/MPS only on macOS).
2. **SingForge shell**: `:ui` extracted from mLabeler, `:singforge` app, projects, toolkit connection, the Data and
   Check steps (doctor, coverage, Open in mLabeler).
3. **DiffSinger end to end**: Model presets, Train with graphs, checkpoint ladder, Export with linter and test
   render. First release.
4. Vocoder fine-tuning.
5. Dictionary editor, IPA tables, language merge wizard, G2P training.
6. RVC.
7. TIFA / WFL / MCFL training (after the WFL recipes and MCFL additions are in versioned repos).
8. TTS (VoxCPM2, Qwen3-TTS).
9. GAME fine-tuning.

## Open questions

- Pin DiffSinger main (`ba031f6`, LYNXNet2 and the probe epoch, no release for 9 months) or v2.5.1 (what current
  voicebanks expect)? Default here: main, with a fallback pin.
- Which base models to publish for fine-tuning (Russian in particular) and on what data licenses.
- Where the WFL training recipes should live (MCFL repo proposed).
- VoxCPM2 memory on 16 GB cards: measure before promising it.
