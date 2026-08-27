package kopo.poly.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class UserInfoDTO {
    private String username;
    private String passwordHash;
    private String name;
    private String email;
    private String role;
    private String wheelchairType;
    private Boolean privacyAgreed;
    private LocalDateTime privacyAgreedAt;

    // 회원가입 전 아이디/이메일 중복 여부를 담는 가상 컬럼
    private String existsYn;
}
