package com.cicdassistant.mapper;

import com.cicdassistant.entity.CompareTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CompareTaskMapper {
    List<CompareTask> findPage(@Param("offset") int offset, @Param("size") int size);
    int count();
    CompareTask findById(@Param("id") Long id);
    int insert(CompareTask t);
    int update(CompareTask t);
    int updateProgress(@Param("id") Long id,
                       @Param("total") int total,
                       @Param("done") int done,
                       @Param("phase") String phase);
    int deleteById(@Param("id") Long id);
}
