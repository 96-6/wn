# Train a WN voice-masking profile

Choose **Runtime → Change runtime type → T4 GPU** for CUDA, or use CPU. Upload a short, clean audio file (2–120 seconds). Training fits a small stochastic neural pitch/energy network and a compact vocal-tract envelope/transition model. CUDA is used for the neural network when available; envelope fitting uses NumPy/CPU. The exported JSON contains derived controls, not a waveform.

Import the JSON in WN 1.4+, add a generator and select **Voice masker**. Noise-first is the recommended starting point; Speech-like blend adds pseudo-voices. The defaults come from the supplied reference comparison and are not guaranteed optimal for another recording. Use the comparison tool for your own measurements.

Cloud execution occurs in your own Colab session. Training fit does not measure human intelligibility or real-room masking.

```python
from pathlib import Path
import subprocess, sys
repo = Path('/content/wn')
if not repo.exists():
    subprocess.run(['git', 'clone', 'https://github.com/96-6/wn.git', str(repo)], check=True)
subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', '-r', str(repo/'tools/requirements-profiler.txt'), 'torch>=2.2,<3'], check=True)

from google.colab import files
uploaded = files.upload()
if not uploaded:
    raise RuntimeError('Select one audio file or an existing WN motion JSON.')
source = next(iter(uploaded))
output = '/content/adjusted-trained.json'
subprocess.run([sys.executable, str(repo/'tools/train_motion_model.py'), source,
                '--output', output, '--report', '/content/training-report.json',
                '--device', 'auto', '--epochs', '3000', '--force'], check=True)
files.download(output)
files.download('/content/training-report.json')

```
