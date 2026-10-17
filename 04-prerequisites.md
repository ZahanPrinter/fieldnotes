# Hackathon Prerequisites & Resources Guide

To work through the hackathon challenge, you'll need three things ready beforehand:

- **The sample dataset:** a fictional oil field with 200 wells and 4 years of history, in the challenge repository. You build your copilot on it.
- **A coding setup:** for example Claude Code, OpenCode, Codex, Cursor, or any other coding agent you're comfortable using.
- **Model access:** through hosted APIs, free model providers, a local model, or any other setup that works for you. You need it twice: for your coding agent, and for the copilot you build, which calls a model to answer questions.

Please have all three set up and tested before the hackathon so you can start building right away.

# Sample Dataset

Repository: [github.com/FlytBaseAILabs/flytbase-ahc-swe-asset-intelligence-dataset](https://github.com/FlytBaseAILabs/flytbase-ahc-swe-asset-intelligence-dataset)

The repository holds the challenge documents and the sample dataset you build on. There is no starter app: you choose your own language, framework, storage and models, and build the copilot from scratch.

## Requirements

- Git, and access to the repository above
- About 30 MB of free disk space
- Any language or stack you like

## 1. Get it

Clone the repository and go into its folder:

```
git clone https://github.com/FlytBaseAILabs/flytbase-ahc-swe-asset-intelligence-dataset.git
cd flytbase-ahc-swe-asset-intelligence-dataset
```

List the folder. You should see the challenge documents and a `sample-data` folder with `fleet` and `assets` inside it.

## 2. What's inside

| Path | What it is |
| :--- | :--- |
| `01-problem-statement.md` | The challenge, what to build and how we judge |
| `02-dataset-guide.md` | Every file and field in the dataset |
| `03-evaluation-queries.md` | Example questions with expected answers, to check your progress |
| `04-prerequisites.md` | This guide |
| `sample-data/fleet/` | Areas, teams and people, well-type manuals, and operating procedures (SOPs) |
| `sample-data/assets/` | One folder per well, WELL-0001 to WELL-0200: details, weekly inspections, maintenance jobs, free-text repair reports, regulator events and human notes |

Read `02-dataset-guide.md` before you start.

# Coding Setup Guide

If you already use Claude Code, OpenCode, Codex, Cursor or another coding agent with your own model access or subscription, keep using that setup.

If you don't have one yet, there are many workable combinations: a coding agent paired with a free model provider or gateway such as OpenRouter, another provider with a free tier, or a local model. Pick whatever works best for you.

Below are two things to help: Gemma 4, a free model you can use in several ways, and an example setup with Claude Code and OpenRouter. You don't need either. Any coding agent and any model work, as long as you have tested them beforehand.

# Free Model Option: Gemma 4

Gemma 4 is Google's open model. It is free and supports tool calling. You can use it in three ways:

| Where you get it | In the copilot you build | With Claude Code |
| :--- | :--- | :--- |
| Google AI Studio | Yes | No |
| OpenRouter | Yes | Yes |
| Your own laptop, with Ollama | Yes | Yes |

## 1. Google AI Studio

Gemma 4 is free of charge on Google's Gemini API.

1. Create an API key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Install the SDK: `pip install google-genai`
3. Make a test call:

```
from google import genai
client = genai.Client(api_key="YOUR-KEY-HERE")
response = client.models.generate_content(model="gemma-4-26b-a4b-it", contents="Say hello.")
print(response.text)
```

Model IDs: `gemma-4-26b-a4b-it` (faster) and `gemma-4-31b-it` (stronger).

Good to know:

- Your exact rate limits are shown in AI Studio. There is no paid tier for Gemma, so you can't buy higher limits.
- Google may use free-tier prompts to improve its products.
- Use it in your copilot, or with coding agents that support Gemini directly. It does not work with Claude Code; for that, use OpenRouter or Ollama.

## 2. OpenRouter

Model IDs: `google/gemma-4-31b-it:free` and `google/gemma-4-26b-a4b-it:free`.

Use them in your copilot through OpenRouter's API, or as the model behind Claude Code. The example setup below shows Claude Code with Gemma 4 on OpenRouter, and explains the free limits. Both models are served by a single provider, so if they stop responding, switch to Ollama or another free model.

## 3. Your own laptop, with Ollama

No account, no key and no rate limits, but it needs a capable machine and runs slower than the hosted options.

**Step 1. Install Ollama** from [ollama.com/download](https://ollama.com/download).

**Step 2. Pick the size that fits your machine.**

| Model | Download | Rough guide |
| :--- | :--- | :--- |
| `gemma4:e2b` | about 5–8 GB | Laptops with 8 GB of memory |
| `gemma4:e4b` | about 7–10 GB | Most laptops with 16 GB of memory |
| `gemma4:12b` | about 8 GB | Laptops with 16 GB or more |
| `gemma4:26b` | about 16–19 GB | Machines with 32 GB or more. Usually much faster than 31b |
| `gemma4:31b` | about 20 GB | Machines with 48 GB or more for comfortable use |

These sizes are for a short context. A 64K context (step 3) needs several GB more, so if memory is tight, use the next size down.

**Step 3. Raise the context length.** Ollama starts with only 4K of context on most laptops (any machine with less than 24 GB of graphics memory). That is too small for a coding agent or for your copilot's prompts, and longer text is silently cut off. Move the context length slider in the Ollama app's settings to 64K, or start the server with:

```
OLLAMA_CONTEXT_LENGTH=64000 ollama serve
```

Your copilot needs this too: the API below can't change it per request.

**Step 4. Download and test the model:**

```
ollama pull gemma4:e4b
ollama run gemma4:e4b "Say hello."
```

Run `ollama ps` to check the model fits in memory.

**Use it in your copilot** through Ollama's OpenAI-compatible API at `http://localhost:11434/v1/`. Ollama ignores the API key, but the client needs some value:

```
from openai import OpenAI
client = OpenAI(base_url="http://localhost:11434/v1/", api_key="ollama")
response = client.chat.completions.create(model="gemma4:e4b", messages=[{"role": "user", "content": "Say hello."}])
print(response.choices[0].message.content)
```

**Use it with Claude Code:** run `ollama launch claude` and pick the Gemma 4 model, or follow [Ollama's Claude Code guide](https://docs.ollama.com/integrations/claude-code). Raise the context length first (step 3). Small local models are much weaker at multi-step coding than hosted ones, so try a real task before the event.

# Example: Claude Code + OpenRouter + Gemma 4

Claude Code is the coding agent here, and OpenRouter provides the model it uses. The same idea applies to other compatible coding agents and model providers.

For this example, have these ready:

- Claude Code installed on your machine ([install guide](https://code.claude.com/docs/en/setup))
- An OpenRouter account
- A terminal you're comfortable using

## 1. Create an OpenRouter account and API key

Go to [openrouter.ai](https://openrouter.ai/) and create an account. Free models don't need credits, but have tight daily limits (see step 2).

Once you're logged in:

1. Open the [API Keys page](https://openrouter.ai/settings/keys).
2. Create a new API key.
3. Copy the generated key. It should look like `sk-or-v1-...`

Keep it private: treat the API key like a password. Don't share it or commit it to your codebase.

## 2. Choose a free model

For this example, we'll use Gemma 4: `google/gemma-4-31b-it:free`

You can browse the currently available free text models here: [OpenRouter - Free Models](https://openrouter.ai/models?max_price=0&output_modalities=text).

Free-model availability changes. Pick a model that supports tool use and copy its model ID. You can switch later if one is rate-limited or doesn't work well with your coding agent. Backups that worked when we wrote this: `google/gemma-4-26b-a4b-it:free` (Gemma 4, faster), `nvidia/nemotron-3-super-120b-a12b:free` and `poolside/laguna-s-2.1:free`.

**Know the free limits.** Free models allow about 20 requests per minute and **50 requests per day** on an account that has never bought credits. A coding agent makes many requests for one task, so 50 can run out within the first hour. If your copilot also uses OpenRouter free models, it shares the same daily limit. Buying at least $10 of credits once raises the free limit to 1,000 requests per day. Plan for this before the event.

## 3. Point Claude Code to OpenRouter

Run these in the same terminal session where you'll use Claude Code.

macOS / Linux / WSL:

```
export ANTHROPIC_BASE_URL="https://openrouter.ai/api"
export ANTHROPIC_AUTH_TOKEN="<your-api-key>"
export ANTHROPIC_API_KEY=""
export ANTHROPIC_MODEL="google/gemma-4-31b-it:free"
export ANTHROPIC_DEFAULT_OPUS_MODEL="google/gemma-4-31b-it:free"
export ANTHROPIC_DEFAULT_SONNET_MODEL="google/gemma-4-31b-it:free"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="google/gemma-4-31b-it:free"
export CLAUDE_CODE_SUBAGENT_MODEL="google/gemma-4-31b-it:free"
```

Windows PowerShell:

```
$env:ANTHROPIC_BASE_URL="https://openrouter.ai/api"
$env:ANTHROPIC_AUTH_TOKEN="<your-api-key>"
$env:ANTHROPIC_API_KEY=""
$env:ANTHROPIC_MODEL="google/gemma-4-31b-it:free"
$env:ANTHROPIC_DEFAULT_OPUS_MODEL="google/gemma-4-31b-it:free"
$env:ANTHROPIC_DEFAULT_SONNET_MODEL="google/gemma-4-31b-it:free"
$env:ANTHROPIC_DEFAULT_HAIKU_MODEL="google/gemma-4-31b-it:free"
$env:CLAUDE_CODE_SUBAGENT_MODEL="google/gemma-4-31b-it:free"
```

Replace `<your-api-key>` with your OpenRouter key. `ANTHROPIC_API_KEY` must be set to an empty value, so Claude Code uses your OpenRouter key instead.

Put the same model ID in all five model lines. Claude Code uses different models for background work and helper agents, and any line you leave out sends those requests to paid Claude models, which fail on a free account. If you picked another model, change all five lines.

Note: these environment variables only apply to the current terminal session. If you open a new terminal, set them again, or add them to your shell profile so they stick.

## 4. Verify that the setup works

Run a tiny one-off request:

```
claude -p "Reply with exactly: OK"
```

If the setup works, you'll get `OK` back.

## 5. Confirm the selected model

Start Claude Code with `claude`, then run `/model` to confirm the model you intended is selected. Run `/status` too: it should show `ANTHROPIC_AUTH_TOKEN` as the auth token and `https://openrouter.ai/api` as the base URL. If it shows a Claude account login or `ANTHROPIC_API_KEY` instead, run `/logout`, set the variables again and restart Claude Code. An earlier Claude account login can override them.

# Test Your Setup Before the Hackathon

Don't stop at the OK test. Try a few small coding tasks and make sure the setup is usable for you. A good test: ask your coding agent to read `02-dataset-guide.md` and write a short script that prints how many inspections WELL-0001 has. It should print 203.

Free models can be rate-limited, temporarily unavailable, slower under load, or less compatible with a particular coding agent. Claude Code is built for Anthropic models and may not work fully with others. If one isn't working reliably:

- Open the free-model catalogue.
- Pick another free model.
- Update your selected model.
- Run the verification again.
- Try a small coding task.

If you see `No endpoints found matching your data policy`, check your [OpenRouter privacy settings](https://openrouter.ai/settings/privacy): some free models need you to allow free endpoints there.

Recommendation: have one or two backup model IDs or an alternative setup ready before the event.

Different tools have different configuration steps. If you choose another combination, follow that tool's documentation and test the complete flow beforehand. OpenRouter also documents integrations for [Claude Code](https://openrouter.ai/docs/cookbook/coding-agents/claude-code-integration) and [Codex CLI](https://openrouter.ai/docs/cookbook/coding-agents/codex-cli).
