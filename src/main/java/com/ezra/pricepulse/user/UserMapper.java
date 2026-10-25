package com.ezra.pricepulse.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT COUNT(*) > 0 FROM users WHERE id = #{userId} AND status = 'ACTIVE'")
    boolean existsActive(long userId);

    @Select("""
            SELECT id, email, password_hash, display_name, status, created_at, updated_at
            FROM users
            WHERE email = #{email}
            """)
    UserAccount findByEmail(String email);

    @Select("""
            SELECT id, email, password_hash, display_name, status, created_at, updated_at
            FROM users
            WHERE id = #{userId}
            """)
    UserAccount findById(long userId);

    int insert(@Param("user") UserAccount user);
}
