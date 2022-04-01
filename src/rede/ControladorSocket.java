// 
// Decompiled by Procyon v0.5.36
// 

package rede;

import exceptions.RespostaTimeoutException;
import exceptions.ConexaoEncerrada;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.net.Socket;

public class ControladorSocket
{
    private static int INSTANCIA_GERADA;
    private final int instancia;
    private static boolean mensagemMostrada;
    private Socket conexao;
    private ObjectInputStream streamIn;
    private ObjectOutputStream streamOut;
    private long ping;
    public static final int ID_PING = -1;
    public static final int ID_DESCONEXAO = -2;
    private long horarioConexaoIniciada;
    private volatile AtomicInteger indiceUnico;
    private volatile boolean flag_finalizar;
    private volatile boolean flag_encerrando;
    private volatile boolean estaConectado;
    private volatile boolean usarThreadsExtras;
    private volatile boolean metodoTerminarChamado;
    private volatile ArrayBlockingQueue<PacoteRede> filaRespostasRecebidas;
    private volatile ArrayBlockingQueue<PacoteRede> filaEnviosPendentes;
    //TODO: private static final long TEMPO_TIMEOUT_RESPOSTA = 5000L;
    private SocketHook socketHook;
    //TODO: private final int build = 5;
    
    public ControladorSocket(final Socket conexaoSocket, final SocketHook hook, final boolean servidor, final boolean threadsExtras) throws ConexaoEncerrada {
        ping = -2L;
        indiceUnico = new AtomicInteger(0);
        flag_finalizar = false;
        flag_encerrando = false;
        estaConectado = false;
        usarThreadsExtras = false;
        metodoTerminarChamado = false;
        filaRespostasRecebidas = new ArrayBlockingQueue<>(10000);
        filaEnviosPendentes = new ArrayBlockingQueue<>(10000);
        
        if (!ControladorSocket.mensagemMostrada) {
            System.out.println("<Controladora de conexões socket iniciado. Modo: " + (servidor ? "servidor" : "cliente") + "; Build: " + 5 + ">");
            ControladorSocket.mensagemMostrada = true;
        }
        
        instancia = ControladorSocket.INSTANCIA_GERADA++;
        conexao = conexaoSocket;
        socketHook = hook;
        usarThreadsExtras = threadsExtras;
        
        try {
            if (servidor) {
                streamOut = new ObjectOutputStream(conexao.getOutputStream());
                streamIn = new ObjectInputStream(conexao.getInputStream());
                
            } else {
                streamIn = new ObjectInputStream(conexao.getInputStream());
                streamOut = new ObjectOutputStream(conexao.getOutputStream());
            }
            
            estaConectado = true;
            
            new Thread("SimpleSocket - Thread de envios " + instancia) {
                @Override
                public void run() {
                    while (!flag_finalizar) {
                        if (!filaEnviosPendentes.isEmpty()) {
                            try {
                                streamOut.writeObject(filaEnviosPendentes.poll());
                                streamOut.flush();
                                
                            } catch (IOException ex2) {
                                filaEnviosPendentes.clear();
                                estaConectado = false;
                                terminarConexao(true);
                            }
                        } else {
                            try {
                                Thread.sleep(5L);
                            } catch (InterruptedException ex) {
                                socketHook.erroFatal(ex);
                            }
                        }
                    }
                }
            }.start();
            
            new Thread("SimpleSocket - Thread de recepção " + instancia) {
                @Override
                public void run() {
                    while (!flag_finalizar) {
                        try {
                            final PacoteRede recebido = (PacoteRede) streamIn.readObject();
                            
                            if (recebido.isResposta()) {
                                recebido.setHorarioRecebido(System.currentTimeMillis());
                                filaRespostasRecebidas.add(recebido);
                            } else {
                                switch (recebido.getID()) {
                                    case -1 -> {
                                        enviarResposta(new PacoteRede(), recebido.getIndice());
                                        continue;
                                    }
                                    
                                    case -2 -> {
                                        enviarResposta(new PacoteRede(true), recebido.getIndice());
                                        terminarConexao(false);
                                        
                                        if (recebido.getDado() != null && recebido.getDado() instanceof String) {
                                            socketHook.conexaoTerminada((String)recebido.getDado());
                                            continue;
                                        }
                                        
                                        socketHook.conexaoTerminada("Pedido de encerramento de conexão recebido.");
                                        continue;
                                    }
                                    
                                    default -> {
                                        pedidoRecebido(recebido);
                                        continue;
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            estaConectado = false;
                            terminarConexao(true);
                        } catch (ClassNotFoundException | ConexaoEncerrada ex2) {
                        
                        }
                    }
                }
            }.start();
            
            horarioConexaoIniciada = System.currentTimeMillis();
            
        } catch (IOException ex) {
            estaConectado = false;
            throw new ConexaoEncerrada("Não é possível comunicar-se com o cliente/servidor.");
        }
    }
    
    private void pedidoRecebido(final PacoteRede pedido) {
        if (usarThreadsExtras) {
            new Thread("SimpleSocket - Thread de pedido " + instancia) {
                @Override
                public void run() {
                    socketHook.pedidoRecebido(pedido);
                }
            }.start();
        } else {
            socketHook.pedidoRecebido(pedido);
        }
    }
    
    public PacoteRede aguardarResposta(final int indice, final long tempoTimeout) throws RespostaTimeoutException {
        final long primeiroTempo = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - primeiroTempo <= tempoTimeout) {
            for (final PacoteRede pacote : filaRespostasRecebidas) {
                if (pacote.getIndice() == indice && pacote.isResposta()) {
                    filaRespostasRecebidas.remove(pacote);
                    return pacote;
                }
            }
            
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ex) {
                socketHook.erroFatal(ex);
            }
        }
        
        throw new RespostaTimeoutException("Tempo esgotado aguardando resposta pela rede.");
    }
    
    public PacoteRede aguardarResposta(final int indice) throws RespostaTimeoutException {
        return aguardarResposta(indice, 5000L);
    }
    
    public void enviarResposta(final PacoteRede pacote, final int indice) throws ConexaoEncerrada {
        if (flag_encerrando) {
            throw new ConexaoEncerrada("A conexão se tornou inativa.");
        }
        
        if (!pacote.isResposta()) {
            throw new IllegalArgumentException("O dado à ser enviado é um pedido e não uma resposta.");
        }
        
        pacote.setIndice(indice);
        filaEnviosPendentes.add(pacote);
    }
    
    public int enviarPedido(final PacoteRede dado) throws ConexaoEncerrada {
        if (flag_encerrando) {
            throw new ConexaoEncerrada("A conexão se tornou inativa.");
        }
        
        if (dado.isResposta()) {
            throw new IllegalArgumentException("O dado à ser enviado é uma resposta e não um pedido.");
        }
        
        final int indice = obterIndiceUnico();
        dado.setIndice(indice);
        filaEnviosPendentes.add(dado);
        
        return indice;
    }
    
    public synchronized void terminarConexao(final boolean chamarHook) {
        if (metodoTerminarChamado) {
            return;
        }
        metodoTerminarChamado = true;
        new Thread("SimpleSocket - Thread de término " + instancia) {
            @Override
            public void run() {
                if (!flag_encerrando) {
                    flag_encerrando = true;
                    
                    if (estaConectado) {
                        while (!filaEnviosPendentes.isEmpty()) {
                            try {
                                Thread.sleep(5L);
                            } catch (InterruptedException ex) {
                            
                            }
                        }
                    }
                    
                    flag_finalizar = true;
                    filaEnviosPendentes.clear();
                    filaRespostasRecebidas.clear();
                    
                    try {
                        streamIn.close();
                    } catch (IOException ex2) {
                    
                    }
                    
                    try {
                        streamOut.close();
                    } catch (IOException ex3) {
                    
                    }
                    
                    try {
                        conexao.close();
                    } catch (IOException ex4) {
                    
                    }
                    
                    if (chamarHook) {
                        socketHook.conexaoTerminada("Conexões encerrada pelo cliente.");
                    }
                }
            }
        }.start();
    }
    
    public synchronized void terminarConexao(final String msg, final boolean chamarHook) {
        if (metodoTerminarChamado) {
            return;
        }
        
        metodoTerminarChamado = true;
        
        new Thread("SimpleSocket - Thread de término " + instancia) {
            @Override
            public void run() {
                if (!flag_encerrando) {
                    if (estaConectado) {
                        final PacoteRede pedido = new PacoteRede(-2, "Conexão encerrada.");
                        
                        if (msg != null) {
                            pedido.setDado(msg);
                        }
                        
                        try {
                            final int x = enviarPedido(pedido);
                            flag_encerrando = true;
                            aguardarResposta(x);
                        } catch (ConexaoEncerrada conexaoEncerrada) {
                        
                        } catch (RespostaTimeoutException ex) {
                        
                        }
                        
                        while (estaConectado && !filaEnviosPendentes.isEmpty()) {
                            try {
                                Thread.sleep(5L);
                            } catch (InterruptedException ex2) {
                            
                            }
                        }
                    }
                    
                    flag_encerrando = true;
                    flag_finalizar = true;
                    filaEnviosPendentes.clear();
                    filaRespostasRecebidas.clear();
                    estaConectado = false;
                    
                    try {
                        streamIn.close();
                    } catch (IOException ex3) {
                    
                    }
                    
                    try {
                        streamOut.close();
                    } catch (IOException ex4) {
                    
                    }
                    
                    try {
                        conexao.close();
                    } catch (IOException ex5) {
                    
                    }
                    
                    if (chamarHook) {
                        socketHook.conexaoTerminada(msg);
                    }
                }
            }
        }.start();
    }
    
    private int obterIndiceUnico() {
        return indiceUnico.getAndIncrement();
    }
    
    public ArrayBlockingQueue<PacoteRede> obterFilaRespostasRecebidas() {
        return filaRespostasRecebidas;
    }
    
    public long obterHorarioConexaoIniciada() {
        return horarioConexaoIniciada;
    }
    
    public boolean conexaoEstaEncerrada() {
        return flag_encerrando || flag_finalizar;
    }
    
    public String obterIP() {
        return conexao.getInetAddress().getHostAddress();
    }
    
    public void renovarPing() {
        final long tempoAnterior = System.currentTimeMillis();
        
        try {
            aguardarResposta(enviarPedido(new PacoteRede(-1)));
            ping = System.currentTimeMillis() - tempoAnterior;
            
        } catch (ConexaoEncerrada | RespostaTimeoutException conexaoEncerrada) {
            //TODO: Fix exceptions
            //final Exception ex2;
            //final Exception ex = ex2;
            ping = -1L;
        }
    }
    
    public long obterPing() {
        return ping;
    }
    
    public void limparRespostasAntigas(final long milis) {
        filaRespostasRecebidas.stream()
                .filter(pacote -> pacote.getHorarioRecebido() + milis >= System.currentTimeMillis())
                .forEachOrdered(pacote -> filaRespostasRecebidas.remove(pacote));
    }
    
    @Override
    public void finalize() {
        try {
            terminarConexao(false);
        } finally {
            try {
                super.finalize();
            } catch (Throwable t) {}
        }
    }
    
    static {
        ControladorSocket.INSTANCIA_GERADA = 0;
        ControladorSocket.mensagemMostrada = false;
    }
}
