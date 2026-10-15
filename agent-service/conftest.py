"""pytest 根 conftest：确保 agent-service 根目录在 sys.path（import app.*）。

注意：导入 app 包本身就会在 chromadb 导入前关闭遥测（见 app/__init__.py）。
"""
