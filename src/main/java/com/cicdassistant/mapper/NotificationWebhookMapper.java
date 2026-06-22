package com.cicdassistant.mapper;

import com.cicdassistant.entity.NotificationWebhook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationWebhookMapper {
    List<NotificationWebhook> findAll();
    NotificationWebhook findById(@Param("id") Long id);
    int insert(NotificationWebhook w);
    int update(NotificationWebhook w);
    int deleteById(@Param("id") Long id);
}
