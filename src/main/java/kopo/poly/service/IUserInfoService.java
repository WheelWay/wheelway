package kopo.poly.service;

import kopo.poly.dto.UserInfoDTO;

public interface IUserInfoService {
    UserInfoDTO getUsernameExists(UserInfoDTO pDTO) throws Exception;

    UserInfoDTO getEmailExists(UserInfoDTO pDTO) throws Exception;

    int insertUserInfo(UserInfoDTO pDTO) throws Exception;
}
