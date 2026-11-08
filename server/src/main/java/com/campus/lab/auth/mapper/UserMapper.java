package com.campus.lab.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.lab.auth.dto.UserRoleAssignment;
import com.campus.lab.auth.entity.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("""
            SELECT r.role_code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.role_id
            WHERE ur.user_id = #{userId}
            ORDER BY r.role_code
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Select("""
            SELECT ur.user_id AS userId, r.role_code AS roleCode
            FROM sys_user_role ur
            JOIN sys_role r ON r.role_id = ur.role_id
            ORDER BY ur.user_id, r.role_code
            """)
    List<UserRoleAssignment> selectAllRoleAssignments();

    @Insert("""
            INSERT INTO sys_user (account, name, password_hash, credit_score, status)
            VALUES (#{account}, #{name}, #{passwordHash}, 100, 'ACTIVE')
            ON DUPLICATE KEY UPDATE user_id = sys_user.user_id
            """)
    int insertIfAbsent(UserEntity user);

    @org.apache.ibatis.annotations.Update("""
            UPDATE sys_user
            SET wechat_openid = #{openid}, wechat_bind_time = #{bindTime}
            WHERE user_id = #{userId} AND wechat_openid IS NULL
            """)
    int bindWechat(@Param("userId") long userId, @Param("openid") String openid,
                   @Param("bindTime") java.time.LocalDateTime bindTime);
}
