#!/bin/bash

# Navipilot Web应用修复部署脚本

echo "修复Navipilot Web应用部署问题..."

# 更新pip
echo "更新pip..."
pip install --upgrade pip

# 清理可能存在的冲突包
echo "清理可能存在的冲突包..."
pip uninstall -y Flask-SQLAlchemy flask-sqlalchemy 2>/dev/null || true

# 使用Linux专用requirements文件安装依赖
echo "使用Linux专用依赖文件安装..."
pip install -r requirements-linux.txt

# 验证关键依赖
echo "验证关键依赖安装..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)"
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)"
python -c "import werkzeug; print('✓ Werkzeug版本:', werkzeug.__version__)"
python -c "import sqlalchemy; print('✓ SQLAlchemy版本:', sqlalchemy.__version__)"

# 测试应用导入
echo "测试应用导入..."
python -c "from app import app; print('✓ 应用导入成功')"

echo "修复完成！现在可以运行应用了。"
