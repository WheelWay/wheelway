package kopo.poly.mapper;

import kopo.poly.dto.UserInfoDTO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IUserInfoMapper {
    UserInfoDTO getLogin(UserInfoDTO pDTO) throws Exception;

    UserInfoDTO getUserId(UserInfoDTO pDTO) throws Exception;

    /** 마이페이지 — 아이디로 내 정보를 꺼낸다. EMAIL 은 AES 인 채로 나온다. */
    UserInfoDTO getMyInfo(UserInfoDTO pDTO) throws Exception;

    int updatePassword(UserInfoDTO pDTO) throws Exception;

    // 회원가입 전 아이디 중복 체크(DB 조회하기)
    UserInfoDTO getUsernameExists(UserInfoDTO pDTO) throws Exception;

    // 회원가입 전 이메일 중복 체크(DB 조회하기)
    UserInfoDTO getEmailExists(UserInfoDTO pDTO) throws Exception;

    // 회원 가입하기(회원정보 등록하기)
    int insertUserInfo(UserInfoDTO pDTO) throws Exception;
}
