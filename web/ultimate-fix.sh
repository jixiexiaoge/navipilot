#!/bin/bash

# Navipilot Web应用终极修复脚本
# 专门解决Linux服务器上Flask-SQLAlchemy ModuleNotFoundError问题

echo "=========================================="
echo "Navipilot Web应用终极修复脚本"
echo "解决Flask-SQLAlchemy ModuleNotFoundError"
echo "=========================================="

# 检查Python版本
echo "检查Python环境..."
python --version
pip --version

# 更新pip到最新版本
echo "更新pip到最新版本..."
pip install --upgrade pip

# 清理所有可能冲突的包
echo "清理所有可能冲突的包..."
pip uninstall -y Flask-SQLAlchemy flask-sqlalchemy Flask Werkzeug SQLAlchemy Jinja2 MarkupSafe itsdangerous click blinker greenlet typing-extensions 2>/dev/null || true

# 清理pip缓存
echo "清理pip缓存..."
pip cache purge 2>/dev/null || true

# 方法1: 使用最小依赖安装
echo "方法1: 使用最小依赖安装..."
pip install --no-cache-dir Flask==2.3.3
pip install --no-cache-dir Flask-SQLAlchemy==3.0.5

# 验证安装
echo "验证安装..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)" 2>/dev/null || echo "✗ Flask导入失败"
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)" 2>/dev/null || echo "✗ Flask-SQLAlchemy导入失败"

# 如果失败，尝试方法2
if ! python -c "import flask_sqlalchemy" 2>/dev/null; then
    echo "方法1失败，尝试方法2: 强制重新安装..."
    
    # 清理并重新安装
    pip uninstall -y Flask-SQLAlchemy flask-sqlalchemy Flask 2>/dev/null || true
    
    # 安装依赖
    pip install --force-reinstall --no-cache-dir Flask==2.3.3
    pip install --force-reinstall --no-cache-dir Werkzeug==2.3.7
    pip install --force-reinstall --no-cache-dir SQLAlchemy>=1.4.18
    pip install --force-reinstall --no-cache-dir Flask-SQLAlchemy==3.0.5
    
    # 安装其他必要依赖
    pip install --no-cache-dir Jinja2>=3.1.2
    pip install --no-cache-dir MarkupSafe>=2.1.3
    pip install --no-cache-dir itsdangerous>=2.1.2
    pip install --no-cache-dir click>=8.1.7
    pip install --no-cache-dir blinker>=1.6.3
    pip install --no-cache-dir greenlet>=1.0.0
    pip install --no-cache-dir typing-extensions>=4.6.0
fi

# 如果还是失败，尝试方法3
if ! python -c "import flask_sqlalchemy" 2>/dev/null; then
    echo "方法2失败，尝试方法3: 使用系统包管理器..."
    
    # 尝试使用系统包管理器
    if command -v apt-get &> /dev/null; then
        echo "检测到apt包管理器，尝试安装系统包..."
        apt-get update
        apt-get install -y python3-flask python3-sqlalchemy python3-flask-sqlalchemy
    elif command -v yum &> /dev/null; then
        echo "检测到yum包管理器，尝试安装系统包..."
        yum install -y python3-flask python3-sqlalchemy python3-flask-sqlalchemy
    fi
fi

# 最终验证
echo "最终验证..."
echo "检查Flask..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)" 2>/dev/null || echo "✗ Flask导入失败"

echo "检查Flask-SQLAlchemy..."
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)" 2>/dev/null || echo "✗ Flask-SQLAlchemy导入失败"

echo "检查Werkzeug..."
python -c "import werkzeug; print('✓ Werkzeug版本:', werkzeug.__version__)" 2>/dev/null || echo "✗ Werkzeug导入失败"

echo "检查SQLAlchemy..."
python -c "import sqlalchemy; print('✓ SQLAlchemy版本:', sqlalchemy.__version__)" 2>/dev/null || echo "✗ SQLAlchemy导入失败"

# 测试应用导入
echo "测试应用导入..."
if python -c "from flask import Flask; from flask_sqlalchemy import SQLAlchemy; print('✓ 核心模块导入成功')" 2>/dev/null; then
    echo "✓ 应用导入测试成功！"
    
    # 尝试创建Flask应用实例
    if python -c "from flask import Flask; from flask_sqlalchemy import SQLAlchemy; app = Flask(__name__); db = SQLAlchemy(app); print('✓ Flask应用实例创建成功')" 2>/dev/null; then
        echo "✓ Flask应用实例创建成功！"
        echo ""
        echo "🎉 修复成功！现在可以运行应用了："
        echo "python app.py"
    else
        echo "✗ Flask应用实例创建失败"
    fi
else
    echo "✗ 应用导入测试失败"
    echo ""
    echo "❌ 修复失败，请尝试以下替代方案："
    echo "1. 使用Docker部署: docker-compose up -d"
    echo "2. 使用虚拟环境: python -m venv venv && source venv/bin/activate"
    echo "3. 联系系统管理员检查Python环境"
fi

echo "=========================================="
