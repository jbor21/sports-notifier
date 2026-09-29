#!/usr/bin/env bash
# Quickstart script for the Sports Notification System
set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
CODE_DIR="$ROOT_DIR/code"

echo "====================================================="
echo "🚀 Sports Notification System - Automated Quickstart"
echo "====================================================="

echo "Checking system prerequisites..."

# 1. Detect missing dependencies
MISSING=()
if ! command -v java &> /dev/null; then MISSING+=("openjdk"); fi
if ! command -v mvn &> /dev/null; then MISSING+=("maven"); fi
if ! command -v redis-server &> /dev/null; then MISSING+=("redis"); fi

# 2. Offer to auto-install if missing and on Mac with Homebrew
if [ ${#MISSING[@]} -ne 0 ]; then
    echo "❌ Missing required tools: ${MISSING[*]}"
    if [[ "$OSTYPE" == "darwin"* ]] && command -v brew &> /dev/null; then
        read -p "Would you like Homebrew to automatically download and install them now? (y/N) " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "Installing dependencies via Homebrew... (this may take a minute)"
            brew install "${MISSING[@]}"
            echo "✅ Installation complete!"
        else
            echo "Please install them manually and try again."
            exit 1
        fi
    else
        echo "Please install Java 17+, Maven, and Redis manually and try again."
        exit 1
    fi
fi

echo "✅ All prerequisites found."

echo ""
echo "Starting Redis (if not already running)..."
if ! redis-cli ping > /dev/null 2>&1; then
    redis-server --daemonize yes
    sleep 1
    if ! redis-cli ping > /dev/null 2>&1; then
        echo "❌ Failed to start Redis. Please start it manually."
        exit 1
    fi
    echo "✅ Redis started."
else
    echo "✅ Redis is already running."
fi

echo ""
echo "Downloading Java dependencies, compiling, and running tests..."
# This downloads all Maven dependencies (Jedis, Gson, etc.), builds JARs, and runs unit tests
mvn -f "$CODE_DIR/pom.xml" clean package

echo ""
echo "🎉 Project built and all tests passed successfully!"
echo ""
read -p "Do you want to launch the Sports Notification System now? (y/N) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    bash scripts/run-all.sh
    echo ""
    echo "====================================================="
    echo "To simulate live sports events, open a NEW terminal window and run:"
    echo "  bash scripts/run-simulator.sh"
    echo "====================================================="
else
    echo "You can start the services later by running: bash scripts/run-all.sh"
fi
