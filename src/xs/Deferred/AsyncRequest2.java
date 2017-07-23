package xs.Deferred;

public interface AsyncRequest2<R> {
	public void apply(R result,Deferred newDefered) throws Exception;
}
