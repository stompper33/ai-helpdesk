import os

# Importing app.main builds the default app. Force the offline provider so tests
# never call OpenAI, even if you have a real key in .env (env vars beat .env).
os.environ["AI_PROVIDER"] = "rules"
