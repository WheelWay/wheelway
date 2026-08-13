package kopo.poly.service;

import kopo.poly.dto.UserInfoDTO;

public interface IUserInfoService {
    UserInfoDTO getLogin(UserInfoDTO pDTO) throws Exception;

    UserInfoDTO searchUserIdOrPasswordProc(UserInfoDTO pDTO) throws Exception;

    int newPasswordProc(UserInfoDTO pDTO) throws Exception;

    UserInfoDTO getUsernameExists(UserInfoDTO pDTO) throws Exception;

    UserInfoDTO getEmailExists(UserInfoDTO pDTO) throws Exception;

    int insertUserInfo(UserInfoDTO pDTO) throws Exception;
}
