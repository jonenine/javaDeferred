package xs.Deferred;

/**
 * ���еķ������ǲ���������쳣�� 
 */
public abstract class Reply{
	/**
	 * �Ƿ�����ִ��,ע��ľ���isImmediatly=true����io�߳���ִ��,����io���ֵ��ٶ�
	 * һ�㲻Ҫʹ��
	 */
	boolean isImmediatly  = false;
	
	public Reply(){}
	
	public Reply(boolean isImmediatly){
		this.isImmediatly = isImmediatly;
	}
	
	
	public abstract Object done(Object d);
	
	/**
	 * reject�ķ���,û��ȷ������,������һ���������,����ֻ��ҵ��ʧ��
	 * ������ҵ������,Ҫ�ж�����
	 * 
	 * һ������,����java���쳣һ��,һֱ�׵���ʽ���õ�ÿ��Reply��,���Ҳ���catch
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
