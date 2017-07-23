package xs.Deferred;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 添加监听器的例子,可能的优点是没有同步的问题
 * 发现我对java的多线程,多线程栈同主存的关系没有搞清楚,特别是什么情况下,某一个线程栈的变量会和主存同步,或是确定主存和某一个线程栈同步
 * 
 * -这个类应该封装
 */
public class Source {
	/**
	 * 这是一个前端采集设备主动上报(没有异步请求过程)的情况,这里采用Deferred方式来处理
	 */
	
	private Deferred def;
	public Source(ExecutorService executor){
		def = new Deferred(executor);
		addFirstListener();
	}
	
	/**
	 * 
	 */
	public synchronized void resolve(Object d){
		def.resolve(d);
	}
	
	private final Reply firstListenerReply = new Reply(true){
		public Object done(Object d) {
			Deferred _def = new Deferred(def.getExecutor());
			def = _def;
			addFirstListener();
			return new Object[]{d,def};
		}

		public void fail(Object f) {
			//因为没有异步请求过程,所以也没有fail
		}
	};
	
	/**
	 * 添加默认监听器 
	 */
	private void addFirstListener(){
		//这里采用一个同步的Reply,同resolve在一个线程中执行
		def.then(firstListenerReply);
	}
	
	
	
	//1.添加监听器将变成如下的形式
	public AtomicBoolean addListener(final Reply reply){
		final AtomicBoolean switchButton = new AtomicBoolean(true);
		Reply listener = new Reply(){
			public Object done(Object d) {
				Object[] _d = (Object[]) d;
				Object  result   = _d[0];
				Deferred nextDef = (Deferred) _d[1];
				
				//1.执行监听器业务,注意需要判断switchButton的状态,有的时候虽然监听器加上了,但switchButton已经取消
				if(switchButton.get()){
					reply.done(result);
					//2.将下一个Deferred也加入监听器
					nextDef.then(this);
				}
				
				return d;
			}
			public void fail(Object f) {
				
			}
		};
		//无论def是否已经resolve,listener都会被执行,并最终让nextDef添加监听器,这样就不会有同步的问题
		def.then(listener);
		
		return switchButton;
	}
	
	
	
	//2.移除这个监听器
	public static void removeListener(AtomicBoolean someSwitchButton){
		someSwitchButton.set(false);
	}
	
	static long start = System.currentTimeMillis();
	
	
	/**
	 * 多线程共享的实例,没有field,可以多线程共享
	 */
	static Reply printReply = new Reply(){
		public Object done(Object d) {
			if((System.currentTimeMillis()-start)>1000){
				System.out.println(d);
				start = System.currentTimeMillis();
			}
			return d;
		}

		public void fail(Object f) {
			
		}
	};
	
	public static void main(String args[]){
		try {
			ExecutorService executor = Executors.newFixedThreadPool(1);
			final Source handler = new Source(executor);
			
			final AtomicBoolean button = handler.addListener(printReply);
			
			new Thread(new Runnable() {
				public void run() {
					try {
						Thread.sleep(5000);
						button.set(false);
						Thread.sleep(5000);
						AtomicBoolean button2 = handler.addListener(printReply);
						Thread.sleep(5000);
						button2.set(false);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}).start();
			
			//
			int i=0;
			while(true){
				handler.resolve(i++);
				Thread.sleep(1);
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
}









