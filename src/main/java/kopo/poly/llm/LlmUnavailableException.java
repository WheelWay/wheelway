package kopo.poly.llm;

/**
 * 모델을 부를 수 없는 상태. <b>'목적지를 못 찾았음' 과 구분하려고 따로 둔다.</b>
 *
 * <p>{@code BusUnavailableException} · {@code GeocodeUnavailableException} 과 같은 이유다.
 * 둘을 섞으면 화면이 거짓말을 한다 — 한도에 걸려 못 부른 것을 "그런 곳을 찾지 못했습니다"
 * 로 돌리면 사용자는 멀쩡한 목적지를 계속 다시 말한다.
 *
 * <p>이 예외가 나면 부르는 쪽이 <b>규칙 기반으로 떨어진다.</b> 챗봇이 멈추지는 않는다.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
