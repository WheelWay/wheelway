package kopo.poly.controller;

import jakarta.servlet.http.HttpServletRequest;
import kopo.poly.dto.MsgDTO;
import kopo.poly.dto.UserInfoDTO;
import kopo.poly.service.IUserInfoService;
import kopo.poly.util.CmmUtil;
import kopo.poly.util.EncryptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@RequestMapping(value = "/user")
@RequiredArgsConstructor
@Controller
public class UserController {

    private final IUserInfoService userInfoService;

    /** 회원가입 화면으로 이동 */
    @GetMapping(value = {"userRegForm", "signup"})
    public String userRegForm() {
        log.info("{}.userRegForm", this.getClass().getName());
        return "/user/signup";
    }

    /** 회원가입 전 아이디 중복체크(Ajax) */
    @ResponseBody
    @PostMapping(value = "getUsernameExists")
    public UserInfoDTO getUsernameExists(HttpServletRequest request) throws Exception {
        log.info("{}.getUsernameExists Start!", this.getClass().getName());

        String username = CmmUtil.nvl(request.getParameter("username"));
        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setUsername(username);

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.getUsernameExists(pDTO))
                .orElseGet(UserInfoDTO::new);

        log.info("{}.getUsernameExists End!", this.getClass().getName());
        return rDTO;
    }

    /** 회원가입 전 이메일 중복체크(Ajax) */
    @ResponseBody
    @PostMapping(value = "getEmailExists")
    public UserInfoDTO getEmailExists(HttpServletRequest request) throws Exception {
        log.info("{}.getEmailExists Start!", this.getClass().getName());

        String email = CmmUtil.nvl(request.getParameter("email"));
        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setEmail(EncryptUtil.encAES128CBC(email));

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.getEmailExists(pDTO))
                .orElseGet(UserInfoDTO::new);

        log.info("{}.getEmailExists End!", this.getClass().getName());
        return rDTO;
    }

    /** 회원가입 처리 */
    @ResponseBody
    @PostMapping(value = "insertUserInfo")
    public MsgDTO insertUserInfo(HttpServletRequest request) {
        log.info("{}.insertUserInfo Start!", this.getClass().getName());

        int result = 0;
        String msg;
        MsgDTO dto = new MsgDTO();

        try {
            String username = CmmUtil.nvl(request.getParameter("username"));
            String password = CmmUtil.nvl(request.getParameter("password"));
            String name = CmmUtil.nvl(request.getParameter("name"));
            String email = CmmUtil.nvl(request.getParameter("email"));
            String wheelchairType = CmmUtil.nvl(request.getParameter("wheelchairType"));
            String privacyAgreed = CmmUtil.nvl(request.getParameter("privacyAgreed"));

            UserInfoDTO pDTO = new UserInfoDTO();
            pDTO.setUsername(username);
            pDTO.setPasswordHash(EncryptUtil.encHashSHA256(password));
            pDTO.setName(name);
            pDTO.setEmail(EncryptUtil.encAES128CBC(email));
            pDTO.setRole("USER");
            pDTO.setWheelchairType(wheelchairType.isBlank() ? null : wheelchairType);
            pDTO.setPrivacyAgreed(Boolean.parseBoolean(privacyAgreed));
            pDTO.setPrivacyAgreedAt(LocalDateTime.now());

            result = userInfoService.insertUserInfo(pDTO);
            msg = result == 1 ? "회원가입되었습니다." : "오류로 인해 회원가입이 실패했습니다.";
        } catch (DuplicateKeyException e) {
            result = 2;
            msg = "이미 가입된 아이디 또는 이메일입니다.";
        } catch (Exception e) {
            log.error("회원가입 처리 중 오류 발생", e);
            msg = "오류로 인해 회원가입이 실패했습니다.";
        }

        dto.setResult(result);
        dto.setMsg(msg);
        log.info("{}.insertUserInfo End!", this.getClass().getName());
        return dto;
    }

}
