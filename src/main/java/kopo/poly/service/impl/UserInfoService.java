package kopo.poly.service.impl;

import kopo.poly.dto.UserInfoDTO;
import kopo.poly.mapper.IUserInfoMapper;
import kopo.poly.service.IUserInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserInfoService implements IUserInfoService {
    private final IUserInfoMapper userInfoMapper;

    @Override
    public UserInfoDTO getUsernameExists(UserInfoDTO pDTO) throws Exception {
        return userInfoMapper.getUsernameExists(pDTO);
    }

    @Override
    public UserInfoDTO getEmailExists(UserInfoDTO pDTO) throws Exception {
        return userInfoMapper.getEmailExists(pDTO);
    }

    @Override
    public int insertUserInfo(UserInfoDTO pDTO) throws Exception {
        return userInfoMapper.insertUserInfo(pDTO);
    }
}
