# PitchShifter

他アプリの再生音（音楽アプリ・動画アプリなど）を Android の `AudioPlaybackCaptureConfiguration` API
でキャプチャし、フェーズボコーダー方式でリアルタイムにキーピッチを変更して再生する Android アプリです。

## 動作要件

- Android 10 (API 29) 以上
- Kotlin + Jetpack Compose、AndroidX のみで実装（外部ライブラリ・NDK不要）

## 重要な制約（必ず確認してください）

Android には「他アプリが再生している音を横取りして加工し、元の音を消す」機能は存在しません。
`AudioPlaybackCaptureConfiguration` は再生中の音声の **コピー** を取得できるだけで、キャプチャ対象アプリの
音声出力そのものは止まりません。そのため：

- 通常モードでは、**元の音声とピッチ変更後の音声が同時に鳴ります**（二重再生になります）。
- 本アプリの自分自身の出力は、フィードバックループ防止のため Android が自動的にキャプチャ対象から除外します。
- アプリ内に「元の音声をミュート（実験的）」トグルを用意しています。ON にすると `STREAM_MUSIC`
  （メディア音量）を 0 にして音源アプリを黙らせ、自分の出力だけ `USAGE_ALARM`（アラーム音量）経路で
  再生する回避策です。機種・OSバージョンによって挙動が異なる可能性がある実験的機能です。
- DRM保護されたコンテンツや、キャプチャを明示的に禁止しているアプリ（`FLAG_NO_SYSTEM_CAPTURE` を設定して
  いるアプリ、多くの動画配信/ゲームアプリなど）の音声はキャプチャできません。

## 使い方

1. Android Studio でこのフォルダ（`PitchShifter/`）を開く（プロジェクトルートとして認識されます）。
2. 実機またはエミュレータ（API 29+）を接続し、Run。
3. アプリ起動後「開始」を押す → マイク権限 → 通知権限（Android 13+）→
   画面/音声キャプチャの確認ダイアログ、の順に許可する。
4. 音楽/動画アプリで何か再生した状態でスライダーを動かすと、ピッチ変更後の音がリアルタイムに再生されます。
5. 「停止」でキャプチャを終了します。

## 構成

```
app/src/main/java/com/rinaemon/pitchshifter/
├── MainActivity.kt        # Compose UI、権限リクエスト、MediaProjection同意フロー
├── CaptureService.kt       # Foreground Service。AudioRecordでキャプチャ→AudioTrackで再生
├── PitchController.kt      # UIとServiceを繋ぐ共有状態（StateFlow）
├── PitchShifterApp.kt       # Application、通知チャンネル作成
├── dsp/FFT.kt               # Radix-2 FFT（Bernsee氏のsmbFftを移植）
└── dsp/PitchShifter.kt       # フェーズボコーダーによるピッチシフト（smbPitchShiftを移植）
```

## ビルド確認について

この開発環境には実機/エミュレータが接続されていないため、`./gradlew assembleDebug` による
コンパイル確認までは実施済みです（成功）。実際の音声キャプチャ・再生動作は、Android Studio から
実機またはエミュレータにインストールして確認してください。

## 既知の調整ポイント

- `PitchShifter` の `fftFrameSize`（既定 1024）・`oversampling`（既定 4）を上げると音質が上がりますが、
  レイテンシとCPU負荷も増えます。
- キャプチャ対象は `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN` に限定しています
  （`CaptureService.startCapture` 内の `addMatchingUsage` 呼び出し）。

## ライセンス

MIT License です。詳しくは [`LICENSE`](LICENSE) を参照してください。

`dsp/FFT.kt` と `dsp/PitchShifter.kt` は Stephan M. Bernsee 氏の smbFft / smbPitchShift を移植したもので、Wide Open License (WOL) に従います。著作権表示とライセンス文は各ファイルの冒頭にあります。
