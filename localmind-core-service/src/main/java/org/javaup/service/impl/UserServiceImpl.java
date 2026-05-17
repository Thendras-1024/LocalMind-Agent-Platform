package org.javaup.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.dto.LoginFormDTO;
import org.javaup.dto.Result;
import org.javaup.dto.UserDTO;
import org.javaup.entity.User;
import org.javaup.entity.UserInfo;
import org.javaup.entity.UserPhone;
import org.javaup.mapper.UserMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IUserPhoneService;
import org.javaup.service.IUserService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.RegexUtils;
import org.javaup.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.javaup.utils.RedisConstants.LOGIN_CODE_KEY;
import static org.javaup.utils.RedisConstants.LOGIN_CODE_TTL;
import static org.javaup.utils.RedisConstants.LOGIN_USER_KEY;
import static org.javaup.utils.RedisConstants.LOGIN_USER_TTL;
import static org.javaup.utils.RedisConstants.USER_SIGN_KEY;
import static org.javaup.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IUserPhoneService userPhoneService;

    @Resource
    private RedisCache redisCache;

    @Override
    public Result<String> sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("发送短信验证码成功，验证码：{}", code);
        return Result.ok(code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        UserPhone userPhone = userPhoneService.lambdaQuery().eq(UserPhone::getPhone, phone).one();
        User user;
        if (userPhone == null) {
            user = createUserWithPhone(phone);
        } else {
            user = lambdaQuery().eq(User::getPhone, userPhone.getPhone()).one();
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(
                tokenKey,
                TimeUnit.SECONDS.convert(LOGIN_USER_TTL, TimeUnit.MINUTES),
                TimeUnit.SECONDS
        );

        try {
            maintainLevelSetMembership(user.getId());
        } catch (Exception e) {
            log.warn("maintainLevelSetMembership failed during login", e);
        }
        return Result.ok(token);
    }

    @Override
    public Result<Void> sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result<Integer> signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setId(snowflakeIdGenerator.nextId());
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);

        UserInfo userInfo = new UserInfo();
        userInfo.setId(snowflakeIdGenerator.nextId());
        userInfo.setUserId(user.getId());
        userInfo.setLevel(1);
        userInfoService.save(userInfo);
        try {
            maintainLevelSetMembership(user.getId());
        } catch (Exception e) {
            log.warn("maintainLevelSetMembership failed during register", e);
        }

        UserPhone userPhone = new UserPhone();
        userPhone.setId(snowflakeIdGenerator.nextId());
        userPhone.setUserId(user.getId());
        userPhone.setPhone(phone);
        userPhoneService.save(userPhone);
        return user;
    }

    private void maintainLevelSetMembership(Long userId) {
        if (userId == null) {
            return;
        }
        UserInfo info = userInfoService.lambdaQuery().eq(UserInfo::getUserId, userId).one();
        if (info == null || info.getLevel() == null || info.getLevel() <= 0) {
            return;
        }
        Integer level = info.getLevel();
        redisCache.addForSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_LEVEL_MEMBERS_TAG_KEY, level),
                userId
        );
    }
}
