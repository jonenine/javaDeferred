package xs.Deferred;

public interface AsyncRequest1<R> {
	public Deferred apply(R result) throws Exception;
}
