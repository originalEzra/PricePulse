package com.ezra.pricepulse.phase0.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Phase 0 MyBatis connection probe; not a business table. */
@Mapper
public interface ProbeMapper {
    @Insert("INSERT INTO phase0_probe (id, payload) VALUES (#{id}, #{payload})")
    int insert(@Param("id") String id, @Param("payload") String payload);

    @Select("SELECT payload FROM phase0_probe WHERE id = #{id}")
    String findPayload(String id);

    @Delete("DELETE FROM phase0_probe WHERE id = #{id}")
    int delete(String id);
}
