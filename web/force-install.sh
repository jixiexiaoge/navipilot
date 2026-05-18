#!/bin/bash

# Navipilot Web应用强制安装脚本
# 解决Linux服务器上Flask-SQLAlchemy安装问题

echo "开始强制安装Navipilot Web应用依赖..."

# 更新pip到最新版本
echo "更新pip到最新版本..."
pip install --upgrade pip

# 清理所有可能冲突的包
echo "清理所有可能冲突的包..."
pip uninstall -y Flask-SQLAlchemy flask-sqlalchemy Flask Werkzeug SQLAlchemy 2>/dev/null || true

# 强制重新安装所有核心依赖
echo "强制重新安装Flask..."
pip install --force-reinstall --no-cache-dir Flask==2.3.3

echo "强制重新安装Werkzeug..."
pip install --force-reinstall --no-cache-dir Werkzeug==2.3.7

echo "强制重新安装SQLAlchemy..."
pip install --force-reinstall --no-cache-dir SQLAlchemy>=1.4.18

echo "强制重新安装Flask-SQLAlchemy..."
pip install --force-reinstall --no-cache-dir Flask-SQLAlchemy==3.0.5

# 安装其他必要依赖
echo "安装其他必要依赖..."
pip install --no-cache-dir Jinja2>=3.1.2
pip install --no-cache-dir MarkupSafe>=2.1.3
pip install --no-cache-dir itsdangerous>=2.1.2
pip install --no-cache-dir click>=8.1.7
pip install --no-cache-dir blinker>=1.6.3
pip install --no-cache-dir greenlet>=1.0.0
pip install --no-cache-dir typing-extensions>=4.6.0

# 验证所有关键依赖
echo "验证所有关键依赖..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)"
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)"
python -c "import werkzeug; print('✓ Werkzeug版本:', werkzeug.__version__)"
python -c "import sqlalchemy; print('✓ SQLAlchemy版本:', sqlalchemy.__version__)"

# 测试应用导入
echo "测试应用导入..."
python -c "from flask import Flask; from flask_sqlalchemy import SQLAlchemy; print('✓ 核心模块导入成功')"

echo "强制安装完成！"
