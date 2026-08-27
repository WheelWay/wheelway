package kopo.poly.stt;

/**
 * 음성인식 서버를 쓸 수 없는 상태. <b>'못 알아들었음' 과 구분하려고 따로 둔다.</b>
 *
 * <p>{@code BusUnavailableException} · {@code GeocodeUnavailableException} ·
 * {@code LlmUnavailableException} 과 같은 이유다. 둘을 섞으면 화면이 거짓말을 한다 —
 * 서버가 안 떠 있는데 "잘 못 알아들었어요" 라고 하면 사용자는 계속 다시 말한다.
 * 고칠 사람은 사용자가 아니라 whisper 를 안 띄운 우리다.
 */
public class SttUnavailableException extends RuntimeException {

    public SttUnavailableException(String message) {
        super(message);
    }

    public SttUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
