<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>WheelWay | 회원가입</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css" rel="stylesheet" integrity="sha384-sRIl4kxILFvY47J16cr9ZwB07vP4J8+LH7qKQnuqkuIAvNWLzeN8tE5YBujZqJLB" crossorigin="anonymous">
    <style>
        :root { --ww-blue:#1688e8; --ww-cream:#ffe1b1; --ww-orange:#eda863; }
        body { min-height:100vh; background:#f5f6f8; font-family:"Noto Sans KR","Malgun Gothic",sans-serif; }
        .page-shell { min-height:100vh; display:flex; align-items:center; justify-content:center; padding:28px max(20px, 2vw); }
        .signup-panel { width:100%; max-width:1940px; min-height:calc(100vh - 56px); display:flex; flex-direction:column; justify-content:center; background:var(--ww-cream); border:2px solid var(--ww-blue); }
        .signup-form { width:100%; max-width:620px; }
        .form-control { min-height:46px; border:2px solid var(--ww-blue); border-radius:1.5rem; }
        .form-control:focus { border-color:var(--ww-blue); box-shadow:0 0 0 .22rem rgb(22 136 232 / 18%); }
        .input-group .form-control { border-radius:1.5rem 0 0 1.5rem; }
        .input-group .btn { min-width:92px; border:2px solid var(--ww-blue); border-left:0; border-radius:0 1.5rem 1.5rem 0; }
        .btn-check-action { background:var(--ww-orange); color:#fff; }
        .btn-check-action:hover { background:#df914b; color:#fff; }
        .btn-signup { background:var(--ww-blue); border-color:var(--ww-blue); border-radius:1.5rem; min-height:48px; }
        .btn-signup:hover { background:#0674cf; border-color:#0674cf; }
        .form-check-input { width:1.7rem; height:1.7rem; margin-top:.05rem; border:2px solid var(--ww-blue); }
        .form-check-input:checked { background-color:var(--ww-blue); border-color:var(--ww-blue); }
        .feedback { min-height:1.2rem; font-size:.8rem; }
        @media (min-width:1200px) {
            .signup-panel { padding:4rem 5rem !important; }
            .signup-form { max-width:640px; }
            .signup-panel h1 { font-size:2rem; margin-bottom:2rem !important; }
        }
        @media (max-width:767.98px) {
            .page-shell { align-items:flex-start; padding:12px; }
            .signup-panel { min-height:auto; padding:2rem 1.25rem !important; }
        }
        @media (max-width:420px) {
            .input-group .btn { min-width:78px; padding-inline:.5rem; font-size:.82rem; }
            .signup-panel h1 { font-size:1.5rem; }
        }
    </style>
</head>
<body>
<main class="container-fluid page-shell">
    <section class="signup-panel p-4 p-md-5" aria-labelledby="signup-title">
        <h1 id="signup-title" class="h3 fw-bold text-center text-decoration-underline mb-4">회원가입</h1>
        <form id="signupForm" class="signup-form mx-auto" novalidate>
            <div class="mb-3">
                <label for="username" class="form-label fw-bold">아이디</label>
                <div class="input-group">
                    <input id="username" class="form-control" type="text" minlength="4" maxlength="20" autocomplete="username" placeholder="아이디 입력" required>
                    <button class="btn btn-check-action fw-bold" id="usernameCheck" type="button">중복확인</button>
                </div>
                <div class="feedback mt-1" id="usernameMessage" aria-live="polite"></div>
            </div>

            <div class="mb-3">
                <label for="password" class="form-label fw-bold">비밀번호</label>
                <div class="input-group">
                    <input id="password" class="form-control" type="password" minlength="8" maxlength="72" autocomplete="new-password" placeholder="비밀번호 입력" required>
                    <button class="btn btn-outline-primary fw-bold" id="passwordToggle" type="button">표시</button>
                </div>
                <div class="form-text ms-2">8~72자 영문, 숫자, 특수문자를 조합해 입력해 주세요.</div>
            </div>

            <div class="mb-3">
                <label for="name" class="form-label fw-bold">이름</label>
                <input id="name" class="form-control" type="text" maxlength="50" autocomplete="name" placeholder="이름 입력" required>
            </div>

            <div class="mb-4">
                <label for="email" class="form-label fw-bold">이메일</label>
                <div class="input-group">
                    <input id="email" class="form-control" type="email" maxlength="175" autocomplete="email" placeholder="이메일 입력" required>
                    <button class="btn btn-check-action fw-bold" id="emailCheck" type="button">중복확인</button>
                </div>
                <div class="feedback mt-1" id="emailMessage" aria-live="polite"></div>
                <div class="form-text ms-2">이메일은 AES128CBC로 암호화하여 저장됩니다.</div>
            </div>

            <fieldset class="mb-4">
                <legend class="fs-6 fw-bold mb-2">휠체어 타입 <span class="text-secondary small">(선택)</span></legend>
                <div class="d-flex gap-4">
                    <div class="form-check d-flex align-items-center gap-2">
                        <input class="form-check-input" id="manual" type="radio" name="wheelchairType" value="MANUAL">
                        <label class="form-check-label fw-bold" for="manual">수동</label>
                    </div>
                    <div class="form-check d-flex align-items-center gap-2">
                        <input class="form-check-input" id="electric" type="radio" name="wheelchairType" value="ELECTRIC">
                        <label class="form-check-label fw-bold" for="electric">전동</label>
                    </div>
                </div>
            </fieldset>

            <div class="form-check d-flex align-items-center gap-2 mb-3">
                <input class="form-check-input" id="privacyAgreement" type="checkbox" required>
                <label class="form-check-label fw-bold" for="privacyAgreement">개인정보 수집 및 이용에 동의합니다.</label>
            </div>
            <div class="feedback mb-2" id="formMessage" aria-live="polite"></div>
            <button class="btn btn-primary btn-signup w-100 fw-bold" type="submit">회원가입</button>
        </form>
    </section>
</main>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js" integrity="sha384-FKyoEForCGlyvwx9Hj09JcYn3nv7wiPVlz7YYwJrWVcXK/BmnVDxM+D2scQbITxI" crossorigin="anonymous"></script>
<script>
    const contextPath = '${pageContext.request.contextPath}';
    const form = document.getElementById('signupForm');
    const submitButton = form.querySelector('.btn-signup');
    const checked = { username: false, email: false };

    function setMessage(id, text, success) {
        const message = document.getElementById(id);
        message.textContent = text;
        message.className = 'feedback mt-1 ' + (success ? 'text-success' : 'text-danger');
        const input = document.getElementById(id.replace('Message', ''));
        if (input) {
            input.classList.toggle('is-valid', success);
            input.classList.toggle('is-invalid', !success);
        }
    }

    async function checkDuplicate(type) {
        const input = document.getElementById(type);
        const value = input.value.trim();
        const valid = type === 'username' ? /^[A-Za-z0-9_]{4,20}$/.test(value) : input.validity.valid;
        if (!valid) {
            checked[type] = false;
            setMessage(type + 'Message', type === 'username' ? '아이디는 영문, 숫자, 밑줄로 4~20자 입력해 주세요.' : '올바른 이메일 주소를 입력해 주세요.', false);
            return false;
        }
        const endpoint = type === 'username' ? 'getUsernameExists' : 'getEmailExists';
        const requestBody = new URLSearchParams();
        requestBody.append(type, value);
        const response = await fetch(contextPath + '/user/' + endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: requestBody.toString()
        });
        if (!response.ok) throw new Error('중복 확인에 실패했습니다.');
        const data = await response.json();
        const available = data.existsYn === 'N';
        checked[type] = available;
        setMessage(type + 'Message', available ? '사용 가능한 ' + (type === 'username' ? '아이디' : '이메일') + '입니다.' : '이미 사용 중입니다.', available);
        return available;
    }

    document.getElementById('usernameCheck').addEventListener('click', () => checkDuplicate('username').catch(error => setMessage('usernameMessage', error.message, false)));
    document.getElementById('emailCheck').addEventListener('click', () => checkDuplicate('email').catch(error => setMessage('emailMessage', error.message, false)));
    ['username', 'email'].forEach(type => document.getElementById(type).addEventListener('input', () => {
        checked[type] = false;
        document.getElementById(type).classList.remove('is-valid', 'is-invalid');
        document.getElementById(type + 'Message').textContent = '';
    }));
    document.getElementById('passwordToggle').addEventListener('click', function () {
        const input = document.getElementById('password');
        input.type = input.type === 'password' ? 'text' : 'password';
        this.textContent = input.type === 'password' ? '표시' : '숨김';
    });

    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!form.checkValidity()) { form.classList.add('was-validated'); return; }
        try {
            if (!checked.username && !await checkDuplicate('username')) return;
            if (!checked.email && !await checkDuplicate('email')) return;
            submitButton.disabled = true;
            const requestBody = new URLSearchParams();
            requestBody.append('username', document.getElementById('username').value.trim());
            requestBody.append('password', document.getElementById('password').value);
            requestBody.append('name', document.getElementById('name').value.trim());
            requestBody.append('email', document.getElementById('email').value.trim());
            requestBody.append('wheelchairType', form.querySelector('input[name="wheelchairType"]:checked')?.value || '');
            requestBody.append('privacyAgreed', document.getElementById('privacyAgreement').checked);
            const response = await fetch(contextPath + '/user/insertUserInfo', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
                body: requestBody.toString()
            });
            const data = await response.json();
            setMessage('formMessage', data.msg, data.result === 1);
            if (data.result === 1) { form.reset(); checked.username = checked.email = false; form.classList.remove('was-validated'); }
        } catch (error) {
            setMessage('formMessage', '서버에 연결하지 못했습니다. Spring Boot 실행 상태를 확인해 주세요.', false);
        } finally { submitButton.disabled = false; }
    });
</script>
</body>
</html>
