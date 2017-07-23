package xs.Deferred;

/**
 * 所有的方法都是不允许出现异常的 
 */
public abstract class Reply{
	/**
	 * 是否立即执行,注意的就是isImmediatly=true会在io线程中执行,拖慢io部分的速度
	 * 一般不要使用
	 */
	boolean isImmediatly  = false;
	
	public Reply(){}
	
	public Reply(boolean isImmediatly){
		this.isImmediatly = isImmediatly;
	}
	
	
	public abstract Object done(Object d);
	
	/**
	 * reject的返回,没有确定类型,可能是一个程序错误,可能只是业务失败
	 * 所以在业务类中,要判断类型
	 * 
	 * 一旦出错,会向java的异常一样,一直抛到链式调用的每个Reply上,而且不能catch
	 */
	public abstract void fail(Object f);
	
	
	public static Reply showExceptionReply = new Reply(){

		public Object done(Object d) {
			return d;
		}

		public void fail(Object f) {
			if(f instanceof Exception) ((Exception)f).printStackTrace();
			else System.err.println("fail for "+f);
		}
	};
	
}
