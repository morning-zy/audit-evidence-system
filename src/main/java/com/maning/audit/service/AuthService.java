package com.maning.audit.service;

import com.maning.audit.common.result.PageResult;
import com.maning.audit.dto.LoginDTO;
import com.maning.audit.vo.LoginVO;
import com.maning.audit.vo.UserSimpleVO;

import java.util.List;

/**
 * 认证服务
 *
 * <p>Service 层用接口 + 实现分离，不是为了"显得规范"，而是有实际理由：
 * ① 事务边界在实现类上（@Transactional），接口不该关心事务
 * ② 未来换实现（比如加短信登录、单点登录），Controller 不用改
 * ③ 便于用 Mockito 做单元测试
 */
public interface AuthService {

    LoginVO login(LoginDTO dto);

    LoginVO currentUser();

    List<UserSimpleVO> listUsers();

    void logout();
}
