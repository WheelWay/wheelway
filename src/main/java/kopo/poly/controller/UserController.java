package kopo.poly.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import kopo.poly.dto.MsgDTO;
import kopo.poly.dto.UserInfoDTO;
import kopo.poly.service.IUserInfoService;
import kopo.poly.util.CmmUtil;
import kopo.poly.util.EncryptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

    /** 로그인 입력 화면으로 이동 */
    @GetMapping(value = "login")
    public String login() {
        log.info("{}.login Start!", this.getClass().getName());
        log.info("{}.login End!", this.getClass().getName());
        return "/user/login";
    }

    /** 로그인 처리(Ajax) 및 세션 저장 */
    @ResponseBody
    @PostMapping(value = "loginProc")
    public MsgDTO loginProc(HttpServletRequest request, HttpSession session) {
        log.info("{}.loginProc Start!", this.getClass().getName());

        int result = 0;
        String msg;
        MsgDTO dto = new MsgDTO();

        try {
            String username = CmmUtil.nvl(request.getParameter("userId"));
            String password = CmmUtil.nvl(request.getParameter("password"));

            UserInfoDTO pDTO = new UserInfoDTO();
            pDTO.setUsername(username);
            pDTO.setPasswordHash(EncryptUtil.encHashSHA256(password));

            UserInfoDTO rDTO = Optional.ofNullable(userInfoService.getLogin(pDTO))
                    .orElseGet(UserInfoDTO::new);

            if (!CmmUtil.nvl(rDTO.getUsername()).isEmpty()) {
                result = 1;
                msg = "로그인 성공했습니다.";
                session.setAttribute("SS_USER_ID", rDTO.getUsername());
                session.setAttribute("SS_USER_NAME", CmmUtil.nvl(rDTO.getName()));
                session.setAttribute("SS_USER_ROLE", CmmUtil.nvl(rDTO.getRole()));
            } else {
                msg = "아이디 또는 비밀번호가 올바르지 않습니다.";
            }
        } catch (Exception e) {
            result = 2;
            msg = "시스템 문제로 로그인이 실패했습니다.";
            log.error("로그인 처리 중 오류 발생", e);
        }

        dto.setResult(result);
        dto.setMsg(msg);
        log.info("{}.loginProc End!", this.getClass().getName());
        return dto;
    }

    /** 로그인 성공 결과 화면으로 이동 */
    @GetMapping(value = "loginResult")
    public String loginResult() {
        log.info("{}.loginResult", this.getClass().getName());
        return "/user/loginResult";
    }

    /** 아이디 찾기 입력 화면 */
    @GetMapping(value = "searchUserId")
    public String searchUserId() {
        return "/user/searchUserId";
    }

    /** 이름과 이메일로 아이디 찾기 */
    @PostMapping(value = "searchUserIdProc")
    public String searchUserIdProc(HttpServletRequest request, Model model) throws Exception {
        String name = CmmUtil.nvl(request.getParameter("userName"));
        String email = CmmUtil.nvl(request.getParameter("email"));

        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setName(name);
        pDTO.setEmail(EncryptUtil.encAES128CBC(email));

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.searchUserIdOrPasswordProc(pDTO))
                .orElseGet(UserInfoDTO::new);
        model.addAttribute("user", rDTO);
        return "/user/searchUserIdResult";
    }

    /** 비밀번호 찾기 입력 화면 */
    @GetMapping(value = "searchPassword")
    public String searchPassword(HttpSession session) {
        session.removeAttribute("NEW_PASSWORD");
        return "/user/searchPassword";
    }

    /** 새 비밀번호 설정 화면 (세션이 없으면 JSP에서 접근 안내를 표시한다) */
    @GetMapping(value = "newPassword")
    public String newPassword() {
        return "/user/newPassword";
    }

    /** 아이디, 이름, 이메일 일치 여부 확인 후 비밀번호 재설정 화면으로 이동 */
    @PostMapping(value = "searchPasswordProc")
    public String searchPasswordProc(HttpServletRequest request, Model model, HttpSession session) throws Exception {
        String username = CmmUtil.nvl(request.getParameter("userId"));
        String name = CmmUtil.nvl(request.getParameter("userName"));
        String email = CmmUtil.nvl(request.getParameter("email"));

        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setUsername(username);
        pDTO.setName(name);
        pDTO.setEmail(EncryptUtil.encAES128CBC(email));

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.searchUserIdOrPasswordProc(pDTO))
                .orElseGet(UserInfoDTO::new);
        model.addAttribute("user", rDTO);

        if (!CmmUtil.nvl(rDTO.getUsername()).isEmpty()) {
            session.setAttribute("NEW_PASSWORD", rDTO.getUsername());
        }
        return "/user/newPassword";
    }

    /** 새 비밀번호 저장 */
    @PostMapping(value = "newPasswordProc")
    public String newPasswordProc(HttpServletRequest request, Model model, HttpSession session) {
        String resetUsername = CmmUtil.nvl((String) session.getAttribute("NEW_PASSWORD"));
        String msg;

        if (resetUsername.isEmpty()) {
            msg = "비정상적인 접근입니다.";
        } else {
            try {
                String password = CmmUtil.nvl(request.getParameter("password"));
                UserInfoDTO pDTO = new UserInfoDTO();
                pDTO.setUsername(resetUsername);
                pDTO.setPasswordHash(EncryptUtil.encHashSHA256(password));

                int result = userInfoService.newPasswordProc(pDTO);
                msg = result == 1 ? "비밀번호가 재설정되었습니다." : "비밀번호 재설정에 실패했습니다.";
            } catch (Exception e) {
                msg = "시스템 문제로 비밀번호 재설정에 실패했습니다.";
                log.error("비밀번호 재설정 중 오류 발생", e);
            } finally {
                session.removeAttribute("NEW_PASSWORD");
            }
        }

        model.addAttribute("msg", msg);
        return "/user/newPasswordResult";
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
