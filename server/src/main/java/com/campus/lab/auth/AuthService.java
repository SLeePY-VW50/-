package com.campus.lab.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.lab.auth.dto.LoginRequest;
import com.campus.lab.auth.dto.LoginResponse;
import com.campus.lab.auth.dto.UserView;
import com.campus.lab.auth.entity.UserEntity;
import com.campus.lab.auth.entity.UserStatus;
import com.campus.lab.auth.mapper.UserMapper;
import com.campus.lab.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "账号或密码错误";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String unavailableAccountHash;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.unavailableAccountHash = passwordEncoder.encode("unavailable-account-placeholder");
    }

    public LoginResponse login(LoginRequest request) {
        return loginForUser(userByCredentials(request.account(), request.password()));
    }

    public UserEntity userByCredentials(String account, String password) {
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getAccount, account));
        if (user == null) {
            passwordEncoder.matches(password, unavailableAccountHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())
                || user.getStatus() != UserStatus.ACTIVE) {
            throw invalidCredentials();
        }
        return user;
    }

    public LoginResponse loginForUser(UserEntity user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw invalidCredentials();
        }
        UserView view = toView(user);
        return new LoginResponse(jwtService.issueToken(view), view);
    }

    public UserView currentUser(String subject) {
        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "未认证或凭证无效");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "未认证或凭证无效");
        }
        return toView(user);
    }

    public List<UserView> listUsers() {
        List<UserEntity> users = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .orderByAsc(UserEntity::getUserId));
        var rolesByUser = userMapper.selectAllRoleAssignments().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        assignment -> assignment.userId(),
                        java.util.stream.Collectors.mapping(
                                assignment -> assignment.roleCode(),
                                java.util.stream.Collectors.toList())));
        return users.stream()
                .map(user -> toView(user, rolesByUser.getOrDefault(user.getUserId(), List.of())))
                .toList();
    }

    public UserView updateUserStatus(long userId, UserStatus status) {
        UserEntity current = userMapper.selectById(userId);
        if (current == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        UserEntity update = new UserEntity();
        update.setUserId(userId);
        update.setStatus(status);
        userMapper.updateById(update);
        return toView(userMapper.selectById(userId));
    }

    private UserView toView(UserEntity user) {
        return toView(user, userMapper.selectRoleCodes(user.getUserId()));
    }

    private UserView toView(UserEntity user, List<String> roles) {
        return new UserView(
                user.getUserId(),
                user.getAccount(),
                user.getName(),
                List.copyOf(roles),
                user.getStatus());
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}
