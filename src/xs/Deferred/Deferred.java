package xs.Deferred;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;




/**
 * 缺乏状态字段
 * 后期改造,可在pipe的时候加索引,即确定每一个deferred的位置
 * 这样在链上的每一步结果,都可以保存下来
 * pipe4fail的时候,也知道是前面的哪一个Deferred出了错误
 */
public class Deferred {
	
	/**
	 * 一个日志方法,可以取得所在文件及行号 
	 */
	public static String log(String log){
		StackTraceElement ste = new Throwable().getStackTrace()[1];
		return log+" in class "+ste.getFileName()+" on line "+ste.getLineNumber();
	}
	
	
	/**
	 * then中的回调都在这里完成
	 */
	private ExecutorService executor;
	
	
	public ExecutorService getExecutor() {
		return executor;
	}


	/**
	 * 同一个线程池的，尽量在一个线程中执行 
	 */
	public void setReplyExecutor(ExecutorService executor) {
		this.executor = executor;
	}
	
	
	public Deferred(ExecutorService executor){
		this.executor = executor;
	}
	
	private List<Reply> replys = new LinkedList<Reply>();
	
	private boolean hasImmediatReply = false;
	
	/**
	 * 实际上注册回调，回调是链式的，即下一个的回调的入参是上一个的结果
	 * 回调有同步和异步的，同步的在resolve（reject）线程立即执行，异步的在executor中排队执行
	 * 
	 * Deferred应该注意三种线程
	 * 1.resolve或reject线程，一般这是一个不确定的线程，在Deferred调度体系之外，也是Deferred产生的原因
	 * 2.reply的执行线程，要不在executor中执行，要不在resolve或reject线程中执行，推荐前者
	 * 3.pipe中，执行AsyncRequest的线程，意义为发起某个异步任务(或使得另外的线程运行异步任务)，比如一个tcp/http请求,肯定在executor执行
	 * 
	 * 所有的方法立即返回
	 * 方法含义
	 * 1.then:在自己的执行环境(线程,线程池)中串行的执行任务,下面的工作还在当前的执行环境中.
	 * 2.pipe:上面的执行链中断,异步任务启动,但不知什么时候才会响应,响应后的工作,即下面的工作在其他的执行环境中执行
	 */
	public synchronized Deferred then(Reply reply){
		if(reply.isImmediatly) hasImmediatReply = true;
		replys.add(reply);
		//如果已经有了响应
		if(d!=null)      _resolve();
		else if(f!=null) _reject();
		
		return this;
	}
	
	/**
	 * 这个回调的工作是complete这个newDeferred
	 * @param newDeferred
	 * @return
	 */
	public synchronized Deferred then(final Deferred newDeferred){
		this.then(new Reply(){
			public Object done(Object d) {
				newDeferred.resolve(d);
				return d;
			}
			public void fail(Object f) {
				newDeferred.reject(f);
			}
		});
		
		return this;
	}
	
	/**
	 * complete这个newDeferred,并切换到新的执行环境
	 * @param newDeferred
	 * @return
	 */
	public synchronized Deferred pipe(final Deferred newDeferred){
		this.then(new Reply(){
			public Object done(Object d) {
				newDeferred.resolve(d);
				return d;
			}
			public void fail(Object f) {
				newDeferred.reject(f);
			}
		});
		
		return newDeferred;
	}
	
	/**
	 * 引为pipe也具有传播错误的特性,所以一旦前面的pipe的任何一个除了错误,都会调用pipe4fail
	 * 而不是仅仅上一个Deferred出错
	 * 
	 * 可以简单理解为，给予第二次机会，用第二次机会的结果来代替已经complete的这个Deferred
	 */
	public synchronized Deferred pipe4fail(final AsyncRequest1 func){
		final Deferred proxy = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				proxy.executor = Deferred.this.executor;
				proxy.resolve(d);
				return d;
			}
			/**
			 * 一旦出错,是按照上一个defer的线程池执行
			 */
			public void fail(Object f) {
				try {
					//一般是执行异步请求
					Deferred newDeferred = func.apply(f);
					//改成newDeferred的执行环境,一般是个内置的隐式执行环境
					proxy.executor = newDeferred.executor;
					//请求回来之后触发proxy
					newDeferred.then(new Reply(){
						public Object done(Object d) {
							proxy.resolve(d);
							return d;
						}
						public void fail(Object f) {
							proxy.reject(f);
						}
					});
					
				} catch (Exception e) {
					proxy.executor = Deferred.this.executor;
					proxy.reject(e);
				}
			}
		}); 
		
		return proxy;
	}
	
	public synchronized Deferred pipe4fail(final AsyncRequest2 func){
		//默认是空的executor,需要自己加的
		final Deferred newDeferred = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				newDeferred.executor = Deferred.this.executor;
				newDeferred.resolve(d);
				return d;
			}

			public void fail(Object f) {
				try {
					func.apply(f,newDeferred);
				} catch (Exception e) {
					//异步请求失败,newDeferred立即失败
					newDeferred.executor = Deferred.this.executor;
					newDeferred.reject(e);
				}
			}
		});
		
		return newDeferred;
	}
	
	public  Deferred pipe(AsyncRequest1 func){
		return pipe(null,func);
	}
	
	/**
	 * 
	 * @param cutOff 如果上一个deferred正确响应的话,是否在这个pipe上阻断异步链,如果确定阻断,接下来的所有deferred将不会有任何响应
	 * 但上一个deferred如果reject,则不影响错误的传播
	 * @param func
	 * @return
	 */
	public synchronized Deferred pipe(final CutOff cutOff,final AsyncRequest1 func){
		final Deferred proxy = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				//没有排除异常
				if(cutOff!=null && cutOff.should(d)) {
					//链式调用从这里中断,已经返回的Deferred得不到任何响应
					return d;
				}
				try {
					//一般是执行异步请求
					Deferred newDeferred = func.apply(d);
					//改成newDeferred的执行环境,一般是个内置的隐式执行环境
					proxy.executor = newDeferred.executor;
					//请求回来之后触发proxy
					newDeferred.then(new Reply(){
						public Object done(Object d) {
							proxy.resolve(d);
							return d;
						}
						public void fail(Object f) {
							proxy.reject(f);
						}
					});
				} catch (Exception e) {
					proxy.executor = Deferred.this.executor;
					proxy.reject(e);
				}
				return d;
			}
			
			/**
			 * 一旦出错,是按照上一个defer的线程池执行
			 */
			public void fail(Object f) {
				proxy.executor = Deferred.this.executor;
				proxy.reject(f);
			}
		}); 
		
		return proxy;
	}
	
	
	/**
	 * 专注于处理错误的业务的reply 
	 */
	public static abstract class FailedReply extends Reply{
		public Object done(Object d) {return d;	}
	}
	
	/**
	 * 肯定不会出错误的reply 
	 */
	public static abstract class AlwaysSuccessReply extends Reply{
		public void fail(Object f) {}
	}
	
	
	public  Deferred pipe(AsyncRequest2 func){
		return pipe(null,func);
	}
	/**
	 * 异步串行方法链,将返回一个新的Deferred对象,只允许调用一次
	 * @param func      注意func会以前面的同步方法链的最后一个reply的结果作为参数
	 */
	public synchronized Deferred pipe(final CutOff cutOff,final AsyncRequest2 func){
		//默认是空的executor,需要自己加的
		final Deferred newDeferred = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				if(cutOff!=null && cutOff.should(d)) {
					//链式调用从这里中断,已经返回的Deferred得不到任何响应
					return d;
				}
				try {
					func.apply(d,newDeferred);
				} catch (Exception e) {
					//异步请求失败,newDeferred立即失败
					newDeferred.reject(e);
				}
				return d;
			}

			public void fail(Object f) {
				newDeferred.executor = Deferred.this.executor;
				newDeferred.reject(f);
			}
		});
		
		return newDeferred;
	}
	
	/**
	 * 代理到其他的线程池上去 
	 */
	public Deferred pipe(ExecutorService executor){
		 final Deferred def = new Deferred(executor);
		 this.then(new Reply(){
			public Object done(Object d) {
				def.resolve(d);
				return d;
			}
			public void fail(Object f) {
				def.reject(f);
			}
		 });
		 
		 return def;
	}
	
	/**
	 * 使用新的调度环境来执行Reply 
	 */
	public synchronized Deferred pipe(final CutOff cutOff,Reply reply,ExecutorService executor){
		final Deferred newDeferred = new Deferred(executor);
		newDeferred.then(reply);
		
		this.then(new Reply(){
			public Object done(Object d) {
				if(cutOff!=null && cutOff.should(d)) {
					//链式调用从这里中断,已经返回的Deferred引用得不到任何响应
					return d;
				}
				
				newDeferred.resolve(d);
				return d;
			}

			public void fail(Object f) {
				newDeferred.reject(f);
			}
		});
		
		return newDeferred;
	}
	
	public Deferred pipe(Reply reply,ExecutorService executor){
		return pipe(null,reply,executor);
	}
	
	/**
	 * 虽然resolve只执行一次,但_resolve却可能多次执行,主要是resolve之后的then
	 */
	private void _resolve(){
		/**
		 * 注意,这里会造成一个问题,就是Immediat执行的reply的执行顺序会提前,同添加的顺序不一致
		 */
		synchronized (this) {
			if(hasImmediatReply){
				Iterator<Reply> it = replys.iterator();
				Reply next;
				while(it.hasNext() && (next = it.next()).isImmediatly){
					try {
						d = next.done(d);
					} catch (Exception e) {
						e.printStackTrace();
					}
					//删掉
					it.remove();
				}
				hasImmediatReply = false;
			}
		}
		
		executor.execute(new Runnable() {
			public void run() {
				synchronized (Deferred.this) {
					Iterator<Reply> it = replys.iterator();
					while(it.hasNext()){
						try {
							d = it.next().done(d);
						} catch (Exception e) {
							e.printStackTrace();
						}
						//删掉
						it.remove();
					}
				}
			}
		});
	}
	
	
	private Object d = null;
	
	
	private AtomicBoolean isComplete = new AtomicBoolean(false);
	
	public boolean isComplete(){
		return isComplete.get();
	}
	/**
	 * 只能执行一次 
	 */
	public synchronized void resolve(Object d){
		if(!isComplete.compareAndSet(false, true)) {
			//throw new RuntimeException();
			return;
		}
		this.d = d;
		
		if(!replys.isEmpty()) _resolve();
	}
	
	
	private Object f = null;
	/**
	 * 只能执行一次 
	 */
	public synchronized void reject(Object f){
		if(!isComplete.compareAndSet(false, true)) {
			//不再抛出异常
			//throw new RuntimeException((f==null?"":f)+"|"+(d==null?"":d));
			return;
		}
		this.f = f;
		
		if(!replys.isEmpty()) _reject();
	}
	
	private void _reject(){
		synchronized (this) {
			if(hasImmediatReply){
				Iterator<Reply> it = replys.iterator();
				Reply next;
				while(it.hasNext() && (next = it.next()).isImmediatly){
					try {
						next.fail(f);
					} catch (Exception e) {
						e.printStackTrace();
					}
					//删掉
					it.remove();
				}
				hasImmediatReply = false;
			}
		}
		executor.execute(new Runnable() {
			public void run() {
				synchronized(Deferred.this){
					Iterator<Reply> it = replys.iterator();
					while(it.hasNext()){
						try {
							it.next().fail(f);
						} catch (Exception e) {
							e.printStackTrace();
						}
						//删掉
						it.remove();
					}
				}
			}
		});
	}
	
	static final Object ok = new Object(); 
	public static Deferred resolvedDeferred(ExecutorService executor){
		Deferred def = new Deferred(executor);
		def.resolve(ok);
		return def;
	}
	
	
	public static Deferred resolvedDeferred(ExecutorService executor,Object ok){
		Deferred def = new Deferred(executor);
		def.resolve(ok);
		return def;
	}
	
	public static Deferred rejectedDeferred(ExecutorService executor,Object error){
		Deferred def = new Deferred(executor);
		def.reject(error);
		return def;
	}
	
	
	/**
	 * 同步并发的Deferred
	 * 所有的defer必须都成功,有一个失败也不行
	 * @param deferreds
	 * @return
	 */
	public static synchronized Deferred synchronize(ExecutorService executor,Deferred... deferreds){
		final Deferred newDeferred = new Deferred(executor);
		
		final AtomicInteger count = new AtomicInteger(deferreds.length);
		final AtomicBoolean isFailed = new AtomicBoolean(false);
		final List list = new ArrayList(deferreds.length);
		
		for(Deferred deferred:deferreds){
			deferred.then(new Reply(){
				public Object done(Object d) {
					//这样保证一旦下面的newDeferred.resolve的list参数是全部数据,否则无法保障
					synchronized (list) {
						if(!isFailed.get()){
							if(count.decrementAndGet()==0) newDeferred.resolve(list);
							else                           list.add(d);
						}
					}
					
					return d;
				}

				public void fail(Object f) {
					synchronized (list) {
						if(isFailed.compareAndSet(false, true)) newDeferred.reject(f);
					}
				}
			});
		}
		
		return newDeferred;
	}
	
	
}











