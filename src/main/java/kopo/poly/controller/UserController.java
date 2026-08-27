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
import java.util.Map;
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

    /** 로그인 세션을 종료한 뒤 메인 화면으로 이동 */
    @GetMapping(value = "logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
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

    /**
     * 아이디 찾기(Ajax). 메인 화면의 로그인 모달이 부른다.
     *
     * <p>{@link #searchUserIdProc} 와 찾는 방법은 같지만 <b>화면 대신 JSON</b> 을 준다.
     * 결과를 모달 안에서 보여주려면 페이지를 넘어가면 안 되기 때문이다.
     * 독립 화면({@code /user/searchUserId})과 그 결과 화면은 그대로 둔다 —
     * 그쪽은 JS 없이도 돌아야 하고, 링크로 바로 들어오는 길이기도 하다.
     *
     * <p><b>조회 결과를 그대로 내보내지 않는다.</b> 지금 {@code getUserId} 가
     * USERNAME·NAME 만 뽑아서 당장은 차이가 없지만, 나중에 SELECT 에 컬럼이 하나 늘면
     * 그것이 조용히 브라우저까지 따라 나간다. 화면이 쓰는 둘만 실어 보낸다.
     *
     * <p>못 찾은 것은 오류가 아니라 정상 답이다({@code found:false}). 화면이
     * '없다' 와 '서버가 죽었다' 를 갈라 말할 수 있어야 한다.
     */
    @ResponseBody
    @PostMapping(value = "searchUserIdAjax")
    public Map<String, Object> searchUserIdAjax(HttpServletRequest request) throws Exception {
        log.info("{}.searchUserIdAjax Start!", this.getClass().getName());

        String name = CmmUtil.nvl(request.getParameter("userName"));
        String email = CmmUtil.nvl(request.getParameter("email"));

        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setName(name);
        pDTO.setEmail(EncryptUtil.encAES128CBC(email));

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.searchUserIdOrPasswordProc(pDTO))
                .orElseGet(UserInfoDTO::new);

        String username = CmmUtil.nvl(rDTO.getUsername());
        boolean found = !username.isEmpty();

        log.info("{}.searchUserIdAjax End! found={}", this.getClass().getName(), found);

        // 못 찾았으면 이름도 돌려주지 않는다. 되돌려줘 봐야 사용자가 방금 친 값이다.
        return Map.of("found", found,
                "name", found ? CmmUtil.nvl(rDTO.getName()) : "",
                "username", username);
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

    /**
     * 비밀번호 찾기 1단계(Ajax) — 본인 확인. 메인 화면의 로그인 모달이 부른다.
     *
     * <p>{@link #searchPasswordProc} 와 확인하는 방법은 같지만 <b>화면 대신 JSON</b> 을 준다.
     * 독립 화면(/user/searchPassword)은 그대로 둔다.
     *
     * <p><b>아이디를 돌려주지 않는다.</b> 화면은 '됐다/아니다'만 알면 되고, 그다음 단계가
     * 쓰는 아이디는 세션에 있다. 굳이 내보내면 비밀번호를 바꿀 수 있는 대상이 브라우저에
     * 한 번 더 적히는 셈이다.
     *
     * <p><b>★ 못 찾으면 세션을 지운다.</b> {@link #searchPasswordProc} 는 성공했을 때만
     * 넣고 실패해도 남겨두는데, 그쪽은 {@link #searchPassword} 화면을 거치면서 지워진다.
     * 모달에는 그 화면을 거치는 길이 없어서 <b>앞서 성공한 값이 그대로 살아남는다</b> —
     * 여기서 지워야 한다.
     */
    @ResponseBody
    @PostMapping(value = "searchPasswordAjax")
    public Map<String, Object> searchPasswordAjax(HttpServletRequest request, HttpSession session)
            throws Exception {
        log.info("{}.searchPasswordAjax Start!", this.getClass().getName());

        UserInfoDTO pDTO = new UserInfoDTO();
        pDTO.setUsername(CmmUtil.nvl(request.getParameter("userId")));
        pDTO.setName(CmmUtil.nvl(request.getParameter("userName")));
        pDTO.setEmail(EncryptUtil.encAES128CBC(CmmUtil.nvl(request.getParameter("email"))));

        UserInfoDTO rDTO = Optional.ofNullable(userInfoService.searchUserIdOrPasswordProc(pDTO))
                .orElseGet(UserInfoDTO::new);

        String username = CmmUtil.nvl(rDTO.getUsername());
        boolean found = !username.isEmpty();

        if (found) {
            session.setAttribute("NEW_PASSWORD", username);
        } else {
            session.removeAttribute("NEW_PASSWORD");
        }

        log.info("{}.searchPasswordAjax End! found={}", this.getClass().getName(), found);
        return Map.of("found", found);
    }

    /**
     * 비밀번호 찾기 2단계(Ajax) — 새 비밀번호 저장.
     *
     * <p>{@link #newPasswordProc} 와 하는 일이 같고 <b>화면 대신 JSON</b> 을 준다.
     * 바꿀 대상은 요청이 아니라 <b>세션</b>({@code NEW_PASSWORD})에서 가져온다 —
     * 화면이 보낸 아이디를 믿으면 1단계를 건너뛰고 남의 비밀번호를 바꿀 수 있다.
     */
    @ResponseBody
    @PostMapping(value = "newPasswordAjax")
    public MsgDTO newPasswordAjax(HttpServletRequest request, HttpSession session) {
        log.info("{}.newPasswordAjax Start!", this.getClass().getName());

        String resetUsername = CmmUtil.nvl((String) session.getAttribute("NEW_PASSWORD"));
        int result = 0;
        String msg;

        if (resetUsername.isEmpty()) {
            msg = "비정상적인 접근입니다. 비밀번호 찾기부터 다시 진행하세요.";
        } else {
            try {
                UserInfoDTO pDTO = new UserInfoDTO();
                pDTO.setUsername(resetUsername);
                pDTO.setPasswordHash(EncryptUtil.encHashSHA256(
                        CmmUtil.nvl(request.getParameter("password"))));

                result = userInfoService.newPasswordProc(pDTO);
                msg = result == 1 ? "비밀번호가 재설정되었습니다." : "비밀번호 재설정에 실패했습니다.";
            } catch (Exception e) {
                msg = "시스템 문제로 비밀번호 재설정에 실패했습니다.";
                log.error("비밀번호 재설정 중 오류 발생", e);
            } finally {
                // 성공이든 실패든 한 번 쓰면 버린다. 남겨두면 창을 닫았다 열어도 계속 바꿀 수 있다.
                session.removeAttribute("NEW_PASSWORD");
            }
        }

        MsgDTO dto = new MsgDTO();
        dto.setResult(result);
        dto.setMsg(msg);

        log.info("{}.newPasswordAjax End! result={}", this.getClass().getName(), result);
        return dto;
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
            String passwordConfirm = CmmUtil.nvl(request.getParameter("passwordConfirm"));
            String name = CmmUtil.nvl(request.getParameter("name"));
            String email = CmmUtil.nvl(request.getParameter("email"));
            String wheelchairType = CmmUtil.nvl(request.getParameter("wheelchairType"));
            String privacyAgreed = CmmUtil.nvl(request.getParameter("privacyAgreed"));

            if (password.isBlank() || !password.equals(passwordConfirm)) {
                msg = "비밀번호와 비밀번호 확인이 일치하지 않습니다.";
                dto.setResult(result);
                dto.setMsg(msg);
                return dto;
            }

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
