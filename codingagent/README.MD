# For test purpose with ollama

For test purpose, you need to launch ollama

```bash
sudo docker run -d --gpus=all -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
```

Remove *--gpus=all*, if your environment don't support *CUDA*

After first executing, you could launch it

```bash
sudo docker container start ollama
```


Install the model llama3.2:3b for unit test purpose 

```bash
sudo docker exec -it ollama ollama run llama3.2:3b
```

But you could install other model to try.
See https://ollama.com/search

You need to choose model "tools" to support callback method.







