package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplatele;

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在或已被删除");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {


        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        //用户在未登录的情况下,不设设置头像等信息
        if (UserHolder.getUser()==null) return Result.ok(records);

        // 查询用户
        records.forEach(blog -> {
            //在首页显示 头像等其他信息
            queryBlogUser(blog);
            //若为true 前端页面会高亮显示
            isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    /**
     * 判断blog是否被当前用户点赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        //1. 获取当前用户信息
        Long userId = UserHolder.getUser().getId();

        //2. 判断当前用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplatele.opsForZSet().score(key, userId.toString());

        //3.如果点赞了，则将isLike设置为true
        blog.setIsLike(BooleanUtil.isTrue(score != null));
    }


    @Override
    public Result likeBlog(Long id) {
        //1. 获取当前用户信息
        Long userId = UserHolder.getUser().getId();
        //2. 如果当前用户未点赞，则点赞数 +1，同时将用户加入set集合
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplatele.opsForZSet().score(key, userId.toString());
        //点赞数 +1
        if (score == null) {
            boolean updateSuccess = update().setSql("liked = liked +1").eq("id", id).update();
            //将用户加入set集合
            if (updateSuccess)
                stringRedisTemplatele.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            //3. 如果当前用户已点赞，则取消点赞，将用户从set集合中移除
            boolean updateSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            //点赞数 -1
            if (updateSuccess) stringRedisTemplatele.opsForZSet().remove(key, userId.toString());
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        Set<String> top5UserId = stringRedisTemplatele.opsForZSet().range(key, 0, 4);

        if (top5UserId == null || top5UserId.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5UserId.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        //select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("保存笔记失败");
        }

        //查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        //推送给粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long followUserId = follow.getUserId();
            //推送
            String key = FEED_KEY + followUserId;
            stringRedisTemplatele.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询该用户收件箱（之前我们存的key是固定前缀 + 粉丝id），所以根据当前用户id就可以查询是否有关注的人发了笔记
        //ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplatele.opsForZSet().
                        reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4. 解析数据，blogId、minTime（时间戳）、offset，这里指定创建的list大小，可以略微提高效率，因为我们知道这个list就得是这么大
        ArrayList<Object> ids = new ArrayList<>(typedTuples.size());

//        if (ids==null||ids.isEmpty()) return Result.ok();
        long minTime = 0;
        int count = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                count++;
            } else {
                minTime = time;
                count = 1;
            }
        }
        //解决SQL的in不能排序问题，手动指定排序为传入的ids
        String idsStr = StrUtil.join(",", ids);

        //5. 根据id查询blog
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idsStr + ")").list();

        for (Blog blog : blogs) {
            //查询发布该blog的用户信息
            queryBlogUser(blog);
            //查询当前用户是否给该blog点过赞
            isBlogLiked(blog);
        }
        //6. 封装结果并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setOffset(count);
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /**
     * 设置用户名和其头像
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
