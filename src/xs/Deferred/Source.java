package xs.Deferred;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * ��Ӽ�����������,���ܵ��ŵ���û��ͬ��������
 * �����Ҷ�java�Ķ��߳�,���߳�ջͬ����Ĺ�ϵû�и����,�ر���ʲô�����,ĳһ���߳�ջ�ı����������ͬ��,����ȷ�������ĳһ���߳�ջͬ��
 * 
 * -�����Ӧ�÷�װ
 */
public class Source {
	/**
	 * ����һ��ǰ�˲ɼ��豸�����ϱ�(û���첽�������)�����,�������Deferred��ʽ������
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
			//��Ϊû���첽�������,����Ҳû��fail
		}
	};
	
	/**
	 * ���Ĭ�ϼ����� 
	 */
	private void addFirstListener(){
		//�������һ��ͬ����Reply,ͬresolve��һ���߳���ִ��
		def.then(firstListenerReply);
	}
	
	
	
	//1.��Ӽ�������������µ���ʽ
	public AtomicBoolean addListener(final Reply reply){
		final AtomicBoolean switchButton = new AtomicBoolean(true);
		Reply listener = new Reply(){
			public Object done(Object d) {
				Object[] _d = (Object[]) d;
				Object  result   = _d[0];
				Deferred nextDef = (Deferred) _d[1];
				
				//1.ִ�м�����ҵ��,ע����Ҫ�ж�switchButton��״̬,�е�ʱ����Ȼ������������,��switchButton�Ѿ�ȡ��
				if(switchButton.get()){
					reply.done(result);
					//2.����һ��DeferredҲ���������
					nextDef.then(this);
				}
				
				return d;
			}
			public void fail(Object f) {
				
			}
		};
		//����def�Ƿ��Ѿ�resolve,listener���ᱻִ��,��������nextDef��Ӽ�����,�����Ͳ�����ͬ��������
		def.then(listener);
		
		return switchButton;
	}
	
	
	
	//2.�Ƴ����������
	public static void removeListener(AtomicBoolean someSwitchButton){
		someSwitchButton.set(false);
	}
	
	static long start = System.currentTimeMillis();
	
	
	/**
	 * ���̹߳����ʵ��,û��field,���Զ��̹߳���
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









