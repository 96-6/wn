# Train a WN procedural motion profile

Choose **Runtime → Change runtime type → T4 GPU** for CUDA, or use the default CPU runtime. The tiny model also trains quickly on CPU. Upload a short, clean audio file (about 2–120 seconds). The script extracts spectral/pitch/energy data and trains a stochastic mixture-density network. It exports a WN JSON profile; no waveform is embedded. Import that JSON in WN 1.3+ and select Oscillating noise + tone.

Cloud execution occurs in your Colab session. Training fit does not measure real-world masking or selective voice cancellation.

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
