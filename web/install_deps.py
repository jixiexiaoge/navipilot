#!/usr/bin/env python3
"""
Navipilot Web应用依赖安装脚本
专门解决Linux服务器上Flask-SQLAlchemy安装问题
"""

import subprocess
import sys
import os

def run_command(command, description):
    """运行命令并显示结果"""
    print(f"正在执行: {description}")
    print(f"命令: {command}")
    try:
        result = subprocess.run(command, shell=True, check=True, capture_output=True, text=True)
        print(f"✓ 成功: {description}")
        if result.stdout:
            print(f"输出: {result.stdout}")
        return True
    except subprocess.CalledProcessError as e:
        print(f"✗ 失败: {description}")
        print(f"错误: {e.stderr}")
        return False

def main():
    print("Navipilot Web应用依赖安装脚本")
    print("=" * 50)
    
    # 更新pip
    if not run_command("pip install --upgrade pip", "更新pip"):
        print("警告: pip更新失败，继续执行...")
    
    # 清理可能冲突的包
    print("\n清理可能冲突的包...")
    run_command("pip uninstall -y Flask-SQLAlchemy flask-sqlalchemy Flask Werkzeug SQLAlchemy", "清理冲突包")
    
    # 安装核心依赖
    dependencies = [
        ("Flask==2.3.3", "安装Flask"),
        ("Flask-SQLAlchemy==3.0.5", "安装Flask-SQLAlchemy"),
        ("Werkzeug==2.3.7", "安装Werkzeug"),
        ("SQLAlchemy>=1.4.18", "安装SQLAlchemy"),
        ("Jinja2>=3.1.2", "安装Jinja2"),
        ("MarkupSafe>=2.1.3", "安装MarkupSafe"),
        ("itsdangerous>=2.1.2", "安装itsdangerous"),
        ("click>=8.1.7", "安装click"),
        ("blinker>=1.6.3", "安装blinker"),
        ("greenlet>=1.0.0", "安装greenlet"),
        ("typing-extensions>=4.6.0", "安装typing-extensions")
    ]
    
    success_count = 0
    for package, description in dependencies:
        if run_command(f"pip install --force-reinstall --no-cache-dir {package}", description):
            success_count += 1
    
    print(f"\n安装结果: {success_count}/{len(dependencies)} 个包安装成功")
    
    # 验证关键依赖
    print("\n验证关键依赖...")
    test_imports = [
        ("import flask", "Flask"),
        ("import flask_sqlalchemy", "Flask-SQLAlchemy"),
        ("import werkzeug", "Werkzeug"),
        ("import sqlalchemy", "SQLAlchemy")
    ]
    
    for import_code, name in test_imports:
        try:
            exec(import_code)
            print(f"✓ {name} 导入成功")
        except ImportError as e:
            print(f"✗ {name} 导入失败: {e}")
    
    # 测试应用导入
    print("\n测试应用导入...")
    try:
        from flask import Flask
        from flask_sqlalchemy import SQLAlchemy
        print("✓ 核心模块导入成功")
        
        # 尝试创建Flask应用实例
        app = Flask(__name__)
        db = SQLAlchemy(app)
        print("✓ Flask应用实例创建成功")
        
    except Exception as e:
        print(f"✗ 应用导入失败: {e}")
        return False
    
    print("\n安装完成！现在可以运行应用了。")
    return True

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
