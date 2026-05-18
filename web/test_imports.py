#!/usr/bin/env python3
"""
Navipilot Web应用导入测试脚本
用于验证所有必要的依赖是否正确安装
"""

import sys

def test_import(module_name, import_statement, description):
    """测试模块导入"""
    try:
        exec(import_statement)
        print(f"✓ {description} - 导入成功")
        return True
    except ImportError as e:
        print(f"✗ {description} - 导入失败: {e}")
        return False
    except Exception as e:
        print(f"✗ {description} - 其他错误: {e}")
        return False

def main():
    print("Navipilot Web应用依赖测试")
    print("=" * 50)
    
    # 测试核心依赖
    tests = [
        ("flask", "import flask", "Flask框架"),
        ("flask_sqlalchemy", "import flask_sqlalchemy", "Flask-SQLAlchemy"),
        ("werkzeug", "import werkzeug", "Werkzeug"),
        ("sqlalchemy", "import sqlalchemy", "SQLAlchemy"),
        ("jinja2", "import jinja2", "Jinja2模板引擎"),
        ("markupsafe", "import markupsafe", "MarkupSafe"),
        ("itsdangerous", "import itsdangerous", "itsdangerous"),
        ("click", "import click", "Click命令行工具"),
        ("blinker", "import blinker", "Blinker信号系统"),
        ("greenlet", "import greenlet", "Greenlet协程"),
        ("typing_extensions", "import typing_extensions", "typing-extensions")
    ]
    
    success_count = 0
    total_count = len(tests)
    
    for module, import_stmt, desc in tests:
        if test_import(module, import_stmt, desc):
            success_count += 1
    
    print(f"\n测试结果: {success_count}/{total_count} 个模块导入成功")
    
    # 测试Flask应用创建
    print("\n测试Flask应用创建...")
    try:
        from flask import Flask
        from flask_sqlalchemy import SQLAlchemy
        
        app = Flask(__name__)
        app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///test.db'
        app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
        
        db = SQLAlchemy(app)
        
        print("✓ Flask应用实例创建成功")
        print("✓ SQLAlchemy数据库连接配置成功")
        
        # 测试数据库连接
        with app.app_context():
            db.create_all()
            print("✓ 数据库表创建成功")
        
        print("✓ 所有测试通过！应用可以正常运行")
        return True
        
    except Exception as e:
        print(f"✗ Flask应用创建失败: {e}")
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
