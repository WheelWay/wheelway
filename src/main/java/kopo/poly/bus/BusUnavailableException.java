package kopo.poly.bus;

/**
 * 버스 정보를 가져올 수 없는 상태. <b>'결과가 없음' 과 구분하려고 따로 둔다.</b>
 *
 * <p>둘을 같이 다루면 화면이 거짓말을 한다. 키가 막혀 못 부른 것을 빈 목록으로 돌리면
 * 사용자는 '이 근처에 정류장이 없구나' 라고 읽는다. 실제로는 서울 API 가
 * 승인 뒤에도 {@code headerCd=7} 을 내고 있는 상태였다.
 *
 * <p>{@code message} 는 그대로 화면에 뜬다. 원인을 사람이 읽을 수 있게 쓸 것.
 */
public class BusUnavailableException extends RuntimeException {

    public BusUnavailableException(String message) {
        super(message);
    }

    public BusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
