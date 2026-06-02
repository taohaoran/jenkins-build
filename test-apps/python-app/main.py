from flask import Flask, jsonify
from datetime import datetime, timezone

app = Flask(__name__)


@app.route("/health")
def health():
    return jsonify({
        "status": "UP",
        "service": "python-app",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    })


@app.route("/")
def root():
    return jsonify({"message": "Hello from Python App"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
