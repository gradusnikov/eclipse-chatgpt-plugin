# For test purpose

For test purpose, you need to launch ollama
```bash
sudo docker run -d --gpus=all -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
```
Remove *--gpus=all*, if your environment don't support *CUDA*


After first executing, you could launch it
```bash
sudo docker container start ollama
```


