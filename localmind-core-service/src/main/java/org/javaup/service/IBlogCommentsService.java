package org.javaup.service;

import org.javaup.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.Result;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 博客评论接口
 * @author: 阿星不是程序员
 **/
public interface IBlogCommentsService extends IService<BlogComments> {

    Result queryCommentsByBlogId(Long blogId, Integer current);

    Result saveComment(BlogComments comment);
}
