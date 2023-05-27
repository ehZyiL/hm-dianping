package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 查看用户探店笔记
     *
     * @param id
     * @return
     */
    Result queryById(Long id);

    Result queryHotBlog(Integer current);

    /**
     * 博客点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 点赞列表查询列表
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 发笔记
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 分页查询收件箱
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
