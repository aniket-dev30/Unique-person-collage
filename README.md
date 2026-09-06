# Unique-Person Video Collage

An Android application that processes portrait videos completely on-device: it detects faces, groups observations belonging to the same person across different moments in the video, counts each person's continuous appearances, and generates a single collage containing one representative image per detected person.

---

## Features

- 🎥 Process portrait videos directly on the device
- 🙂 Detect faces using Google ML Kit
- 🧠 Generate face embeddings using MobileFaceNet (TensorFlow Lite)
- 🎯 Align faces using eye landmarks before embedding for more consistent results
- 👥 Track continuous appearances and cluster them into unique identities
- 🔢 Count each person's number of continuous appearances
- ⭐ Select a representative frame per person based on image quality (frontal pose, sharpness, eyes open, expression, and face visibility)
- 🖼️ Generate one collage per video, showing every unique person found
- 💾 Save the generated collage to the device gallery
- 📤 Share the collage using the Android share sheet
- ⚡ Face detection, embedding, tracking, clustering, and collage generation are performed on-device
- 📊 Live processing progress is shown to the user during the pipeline

---

## Tech Stack

- **Kotlin**
- **Android / Jetpack Compose**
- **Google ML Kit** – Face Detection
- **TensorFlow Lite** – on-device inference runtime
- **MobileFaceNet** – 192-dimensional face embedding model
- **Android MediaStore** – saving the collage to the gallery
- **Kotlin Coroutines / StateFlow** – background processing and progress reporting

### Minimum SDK

`Android 8.0 (API 26)`

---

## How It Works

The video is processed through a sequential on-device pipeline:

```text
Video
  ↓
Frame Sampling
  ↓
Face Detection
  ↓
Face Alignment
  ↓
Face Embedding
  ↓
Quality Scoring
  ↓
Appearance Tracking
  ↓
Identity Clustering
  ↓
Representative Selection
  ↓
Appearance Counting
  ↓
Collage Generation
  ↓
Save to Gallery / Share
```

Each stage is implemented as an isolated component under `pipeline/`.

### Frame Sampling

The pipeline samples approximately **8 frames per second** from the input video.

For a 30-second video, this produces roughly 240 sampled frames, providing a balance between temporal coverage and processing time.

### Face Detection

Google ML Kit's accurate face detector is used to detect faces and obtain facial landmarks and classification information.

The detection stage provides information such as:

* Face bounding box
* Eye landmarks
* Head rotation
* Eye-open probabilities
* Smiling probability

### Face Alignment

Detected faces are aligned using eye landmarks before generating embeddings.

The aligned face is resized to:

```text
112 × 112
```

This provides a more consistent input for the face embedding model.

### Face Embeddings

MobileFaceNet is used to generate a **192-dimensional face embedding** for each detected face.

The embeddings are L2-normalized and compared using cosine similarity.

Model input:

```text
Shape: [1, 112, 112, 3]
Type: FLOAT32
```

Model output:

```text
Shape: [1, 192]
Type: FLOAT32
```

The model is bundled with the application and inference is performed locally on the device.

### Quality Scoring

Each face observation receives a quality score based on factors including:

* Frontal face orientation
* Image sharpness
* Eyes being open
* Smile probability
* Face visibility / clipping

These scores are used when selecting the best representative image for each identified person.

### Appearance Tracking

Face detections across nearby sampled frames are connected into tracks using:

* Bounding-box overlap (IoU)
* Face embedding similarity
* A limited frame gap tolerance

This allows short detection failures caused by motion blur or brief changes in pose without unnecessarily splitting one continuous appearance.

### Identity Clustering

Appearance tracks are grouped into unique identities using cosine similarity between their mean embeddings.

A secondary singleton-rescue step helps assign isolated tracks to an existing identity when the similarity is sufficiently strong, while avoiding matches between tracks that overlap in time.

### Representative Selection

For each identified person, observations are scored using image quality together with additional preferences such as:

* A clear/solo view of the person
* Adequate face size
* Good frontal pose
* Sharpness
* Eyes open
* Pleasant expression
* Minimal face clipping

The highest-scoring suitable observation is selected as the person's representative image.

### Appearance Counting

Appearance counting is performed after identity clustering.

If tracking temporarily splits one continuous appearance into adjacent tracks, the counting stage combines tracks belonging to the same identified person when the temporal gap is short and their identity similarity is sufficiently high.

This produces the final continuous appearance count shown in the UI.

---

## Project Structure

```text
app/src/main/java/com/iykyk/collageapp/
├── MainActivity.kt
│
├── collage/
│   └── CollageGenerator.kt        # Builds the final collage bitmap
│
├── pipeline/
│   ├── Models.kt                  # FaceObservation, Track, PersonResult, etc.
│   ├── FrameExtractor.kt          # Samples frames from the input video
│   ├── FaceDetectionStage.kt      # ML Kit face detection wrapper
│   ├── FaceAligner.kt             # Eye-landmark based face alignment
│   ├── FaceEmbedder.kt            # MobileFaceNet TFLite inference
│   ├── Sharpness.kt               # Laplacian-variance sharpness measure
│   ├── QualityScorer.kt           # Representative-frame quality scoring
│   ├── AppearanceTracker.kt       # Frame-to-frame appearance tracking
│   ├── Clusterer.kt               # Groups appearance tracks into identities
│   └── VideoPipeline.kt           # Orchestrates the complete pipeline
│
├── ui/
│   ├── MainScreen.kt              # Compose UI
│   └── AppViewModel.kt            # UI state and pipeline invocation
│
└── util/
    ├── MediaStoreSaver.kt         # Saves the collage to the device gallery
    └── ShareUtil.kt               # Android Share Sheet integration
```

---

## Design Overview

The application separates the processing pipeline from the UI layer.

The `VideoPipeline` coordinates the processing stages, while individual components handle detection, alignment, embedding, quality scoring, tracking, clustering, and collage generation independently.

Long-running video processing is executed away from the main UI thread using Kotlin coroutines, while `StateFlow` is used to expose processing state and progress to the Compose UI.

This keeps the UI responsive while the video is being processed.

---

## Model

The application uses:

**MobileFaceNet**

The TensorFlow Lite model is stored in:

```text
app/src/main/assets/mobilefacenet.tflite
```

Model configuration:

```text
Input:  112 × 112 RGB image
Output: 192-dimensional embedding
Runtime: TensorFlow Lite
```

The model runs entirely on-device.

---

## Gallery Output

Generated collages are saved using Android `MediaStore`.

On Android 10 and higher, the collage is saved under:

```text
Pictures/IykykCollage/
```

The application does not require a backend service for video processing or identity clustering.

---

## Building the Project

### Requirements

* Android Studio
* Android SDK
* Android device or emulator
* Android 8.0 (API 26) or higher

### Steps

1. Clone the repository.

```bash
git clone https://github.com/aniket-dev30/Unique-person-collage.git
```

2. Open the project in Android Studio.

3. Allow Gradle dependencies to sync.

4. Verify that the following model is present:

```text
app/src/main/assets/mobilefacenet.tflite
```

5. Build and run the application on an Android device or emulator running API 26 or higher.

---

## Usage

1. Launch the application.
2. Select a portrait video from the device.
3. Wait while the video is processed. Progress is displayed during processing.
4. Review the generated collage and detected people.
5. Review the appearance count shown for each person.
6. Save the collage to the device gallery or share it using the Android Share Sheet.

---

## Testing

The application has been tested against the three approximately 30-second portrait video samples supplied with the assignment.

Testing covers:

* Face detection
* Face alignment
* Face embedding generation
* Unique-person grouping
* Continuous appearance counting
* Representative image selection
* Collage generation
* Save to Gallery
* Android Share Sheet
* End-to-end pipeline stability

---

## Privacy

All face detection, face alignment, embedding generation, appearance tracking, identity clustering, and collage generation are performed on-device.

No backend service is required for the core video-processing pipeline.
