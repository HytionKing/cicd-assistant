package com.cicdassistant.mapper;

import com.cicdassistant.entity.Repo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RepoMapper {
    List<Repo> findAll();
    Repo findById(@Param("id") Long id);
    Repo findByName(@Param("name") String name);
    int insert(Repo repo);
    int update(Repo repo);
    int deleteById(@Param("id") Long id);
}
