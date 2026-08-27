package kopo.poly.geocode;

/**
 * 주소를 좌표로 바꿀 수 없는 상태. <b>'그런 주소가 없음' 과 구분하려고 따로 둔다.</b>
 *
 * <p>{@code BusUnavailableException} 을 따로 둔 것과 같은 이유다. 둘을 섞으면 화면이
 * 거짓말을 한다 — 키가 막혀 못 부른 것을 '주소를 찾지 못했습니다' 로 돌리면 사용자는
 * 멀쩡한 자기 집 주소를 계속 다시 입력한다. 고칠 사람은 사용자가 아니라 우리다.
 *
 * <p>{@code message} 는 그대로 화면에 뜬다. 원인을 사람이 읽을 수 있게 쓸 것.
 */
public class GeocodeUnavailableException extends RuntimeException {

    public GeocodeUnavailableException(String message) {
        super(message);
    }

    public GeocodeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
