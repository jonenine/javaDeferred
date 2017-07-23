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
 * ȱ��״̬�ֶ�
 * ���ڸ���,����pipe��ʱ�������,��ȷ��ÿһ��deferred��λ��
 * ���������ϵ�ÿһ�����,�����Ա�������
 * pipe4fail��ʱ��,Ҳ֪����ǰ�����һ��Deferred���˴���
 */
public class Deferred {
	
	/**
	 * һ����־����,����ȡ�������ļ����к� 
	 */
	public static String log(String log){
		StackTraceElement ste = new Throwable().getStackTrace()[1];
		return log+" in class "+ste.getFileName()+" on line "+ste.getLineNumber();
	}
	
	
	/**
	 * then�еĻص������������
	 */
	private ExecutorService executor;
	
	
	public ExecutorService getExecutor() {
		return executor;
	}


	/**
	 * ͬһ���̳߳صģ�������һ���߳���ִ�� 
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
	 * ʵ����ע��ص����ص�����ʽ�ģ�����һ���Ļص����������һ���Ľ��
	 * �ص���ͬ�����첽�ģ�ͬ������resolve��reject���߳�����ִ�У��첽����executor���Ŷ�ִ��
	 * 
	 * DeferredӦ��ע�������߳�
	 * 1.resolve��reject�̣߳�һ������һ����ȷ�����̣߳���Deferred������ϵ֮�⣬Ҳ��Deferred������ԭ��
	 * 2.reply��ִ���̣߳�Ҫ����executor��ִ�У�Ҫ����resolve��reject�߳���ִ�У��Ƽ�ǰ��
	 * 3.pipe�У�ִ��AsyncRequest���̣߳�����Ϊ����ĳ���첽����(��ʹ��������߳������첽����)������һ��tcp/http����,�϶���executorִ��
	 * 
	 * ���еķ�����������
	 * ��������
	 * 1.then:���Լ���ִ�л���(�߳�,�̳߳�)�д��е�ִ������,����Ĺ������ڵ�ǰ��ִ�л�����.
	 * 2.pipe:�����ִ�����ж�,�첽��������,����֪ʲôʱ��Ż���Ӧ,��Ӧ��Ĺ���,������Ĺ�����������ִ�л�����ִ��
	 */
	public synchronized Deferred then(Reply reply){
		if(reply.isImmediatly) hasImmediatReply = true;
		replys.add(reply);
		//����Ѿ�������Ӧ
		if(d!=null)      _resolve();
		else if(f!=null) _reject();
		
		return this;
	}
	
	/**
	 * ����ص��Ĺ�����complete���newDeferred
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
	 * complete���newDeferred,���л����µ�ִ�л���
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
	 * ��ΪpipeҲ���д������������,����һ��ǰ���pipe���κ�һ�����˴���,�������pipe4fail
	 * �����ǽ�����һ��Deferred����
	 * 
	 * ���Լ����Ϊ������ڶ��λ��ᣬ�õڶ��λ���Ľ���������Ѿ�complete�����Deferred
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
			 * һ������,�ǰ�����һ��defer���̳߳�ִ��
			 */
			public void fail(Object f) {
				try {
					//һ����ִ���첽����
					Deferred newDeferred = func.apply(f);
					//�ĳ�newDeferred��ִ�л���,һ���Ǹ����õ���ʽִ�л���
					proxy.executor = newDeferred.executor;
					//�������֮�󴥷�proxy
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
		//Ĭ���ǿյ�executor,��Ҫ�Լ��ӵ�
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
					//�첽����ʧ��,newDeferred����ʧ��
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
	 * @param cutOff �����һ��deferred��ȷ��Ӧ�Ļ�,�Ƿ������pipe������첽��,���ȷ�����,������������deferred���������κ���Ӧ
	 * ����һ��deferred���reject,��Ӱ�����Ĵ���
	 * @param func
	 * @return
	 */
	public synchronized Deferred pipe(final CutOff cutOff,final AsyncRequest1 func){
		final Deferred proxy = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				//û���ų��쳣
				if(cutOff!=null && cutOff.should(d)) {
					//��ʽ���ô������ж�,�Ѿ����ص�Deferred�ò����κ���Ӧ
					return d;
				}
				try {
					//һ����ִ���첽����
					Deferred newDeferred = func.apply(d);
					//�ĳ�newDeferred��ִ�л���,һ���Ǹ����õ���ʽִ�л���
					proxy.executor = newDeferred.executor;
					//�������֮�󴥷�proxy
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
			 * һ������,�ǰ�����һ��defer���̳߳�ִ��
			 */
			public void fail(Object f) {
				proxy.executor = Deferred.this.executor;
				proxy.reject(f);
			}
		}); 
		
		return proxy;
	}
	
	
	/**
	 * רע�ڴ�������ҵ���reply 
	 */
	public static abstract class FailedReply extends Reply{
		public Object done(Object d) {return d;	}
	}
	
	/**
	 * �϶�����������reply 
	 */
	public static abstract class AlwaysSuccessReply extends Reply{
		public void fail(Object f) {}
	}
	
	
	public  Deferred pipe(AsyncRequest2 func){
		return pipe(null,func);
	}
	/**
	 * �첽���з�����,������һ���µ�Deferred����,ֻ�������һ��
	 * @param func      ע��func����ǰ���ͬ�������������һ��reply�Ľ����Ϊ����
	 */
	public synchronized Deferred pipe(final CutOff cutOff,final AsyncRequest2 func){
		//Ĭ���ǿյ�executor,��Ҫ�Լ��ӵ�
		final Deferred newDeferred = new Deferred(null);
		
		this.then(new Reply(){
			public Object done(Object d) {
				if(cutOff!=null && cutOff.should(d)) {
					//��ʽ���ô������ж�,�Ѿ����ص�Deferred�ò����κ���Ӧ
					return d;
				}
				try {
					func.apply(d,newDeferred);
				} catch (Exception e) {
					//�첽����ʧ��,newDeferred����ʧ��
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
	 * �����������̳߳���ȥ 
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
	 * ʹ���µĵ��Ȼ�����ִ��Reply 
	 */
	public synchronized Deferred pipe(final CutOff cutOff,Reply reply,ExecutorService executor){
		final Deferred newDeferred = new Deferred(executor);
		newDeferred.then(reply);
		
		this.then(new Reply(){
			public Object done(Object d) {
				if(cutOff!=null && cutOff.should(d)) {
					//��ʽ���ô������ж�,�Ѿ����ص�Deferred���õò����κ���Ӧ
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
	 * ��Ȼresolveִֻ��һ��,��_resolveȴ���ܶ��ִ��,��Ҫ��resolve֮���then
	 */
	private void _resolve(){
		/**
		 * ע��,��������һ������,����Immediatִ�е�reply��ִ��˳�����ǰ,ͬ��ӵ�˳��һ��
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
					//ɾ��
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
						//ɾ��
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
	 * ֻ��ִ��һ�� 
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
	 * ֻ��ִ��һ�� 
	 */
	public synchronized void reject(Object f){
		if(!isComplete.compareAndSet(false, true)) {
			//�����׳��쳣
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
					//ɾ��
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
						//ɾ��
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
	 * ͬ��������Deferred
	 * ���е�defer���붼�ɹ�,��һ��ʧ��Ҳ����
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
					//������֤һ�������newDeferred.resolve��list������ȫ������,�����޷�����
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











