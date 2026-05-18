#!/bin/bash

# Navipilot Web应用依赖安装脚本

echo "开始安装Navipilot Web应用依赖..."

# 更新pip
echo "更新pip..."
pip install --upgrade pip

# 强制安装Flask-SQLAlchemy
echo "安装Flask-SQLAlchemy..."
pip install --force-reinstall Flask-SQLAlchemy==3.0.5

# 安装其他依赖
echo "安装其他依赖..."
pip install Flask==2.3.3
pip install Werkzeug==2.3.7
pip install SQLAlchemy>=1.4.18
pip install Jinja2>=3.1.2
pip install MarkupSafe>=2.1.3
pip install itsdangerous>=2.1.2
pip install click>=8.1.7
pip install blinker>=1.6.3
pip install greenlet>=1.0.0
pip install typing-extensions>=4.6.0

# 验证安装
echo "验证依赖安装..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)"
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)"
python -c "import sqlalchemy; print('✓ SQLAlchemy版本:', sqlalchemy.__version__)"

echo "依赖安装完成！"
