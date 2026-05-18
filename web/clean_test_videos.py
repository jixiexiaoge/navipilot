#!/usr/bin/env python3
"""
清理测试视频数据脚本
用于删除数据库中的测试视频数据
"""

from flask import Flask
from flask_sqlalchemy import SQLAlchemy
import os

# 创建Flask应用
app = Flask(__name__)

# 配置数据库
basedir = os.path.abspath(os.path.dirname(__file__))
app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{os.path.join(basedir, "database.db")}'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

db = SQLAlchemy(app)

# 视频表模型
class Video(db.Model):
    __tablename__ = 'videos'
    
    id = db.Column(db.Integer, primary_key=True)
    video_title = db.Column(db.String(200), nullable=False)
    video_link = db.Column(db.String(500), nullable=False)
    
    def to_dict(self):
        return {
            'id': self.id,
            'video_title': self.video_title,
            'video_link': self.video_link
        }

def clean_test_videos():
    """清理测试视频数据"""
    with app.app_context():
        # 获取所有视频
        videos = Video.query.all()
        
        print(f"当前数据库中有 {len(videos)} 个视频:")
        for video in videos:
            print(f"  ID: {video.id}, 标题: {video.video_title}, 链接: {video.video_link}")
        
        # 查找测试视频（根据标题特征）
        test_video_titles = [
            'Navipilot使用教程 - 基础操作',
            '高级功能演示 - 语音控制',
            '安全驾驶技巧分享'
        ]
        
        test_videos = Video.query.filter(Video.video_title.in_(test_video_titles)).all()
        
        if test_videos:
            print(f"\n找到 {len(test_videos)} 个测试视频，准备删除:")
            for video in test_videos:
                print(f"  删除: {video.video_title}")
                db.session.delete(video)
            
            db.session.commit()
            print("✓ 测试视频删除完成！")
        else:
            print("\n没有找到测试视频，无需删除。")
        
        # 显示剩余视频
        remaining_videos = Video.query.all()
        print(f"\n删除后剩余 {len(remaining_videos)} 个视频:")
        for video in remaining_videos:
            print(f"  ID: {video.id}, 标题: {video.video_title}, 链接: {video.video_link}")

if __name__ == "__main__":
    clean_test_videos()
