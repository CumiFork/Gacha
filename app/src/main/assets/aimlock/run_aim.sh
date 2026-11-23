#!/system/bin/sh
# root launcher: ptrace aimlock using RVAs extracted from dump.cs
DIR=$(cd "$(dirname "$0")" && pwd)
PY=/data/data/com.termux/files/usr/bin/python3
CMD="cd '$DIR' && '$PY' ff_aim.py --config '$DIR/ff_rvas.json'"
if [ "$(id -u)" -ne 0 ]; then
  exec su -c "setenforce 0 2>/dev/null; $CMD"
fi
setenforce 0 2>/dev/null
cd "$DIR" && exec "$PY" ff_aim.py --config "$DIR/ff_rvas.json"
