// 
// Decompiled by Procyon v0.5.36
// 

package rede;

import java.util.Iterator;
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
    private static final long TEMPO_TIMEOUT_RESPOSTA = 5000L;
    private SocketHook socketHook;
    private final int build = 5;
    
    public ControladorSocket(final Socket conexaoSocket, final SocketHook hook, final boolean servidor, final boolean threadsExtras) throws ConexaoEncerrada {
        this.ping = -2L;
        this.indiceUnico = new AtomicInteger(0);
        this.flag_finalizar = false;
        this.flag_encerrando = false;
        this.estaConectado = false;
        this.usarThreadsExtras = false;
        this.metodoTerminarChamado = false;
        this.filaRespostasRecebidas = new ArrayBlockingQueue<PacoteRede>(10000);
        this.filaEnviosPendentes = new ArrayBlockingQueue<PacoteRede>(10000);
        if (!ControladorSocket.mensagemMostrada) {
            System.out.println("<Controladora de conex\u00f5es socket iniciado. Modo: " + (servidor ? "servidor" : "cliente") + "; Build: " + 5 + ">");
            ControladorSocket.mensagemMostrada = true;
        }
        this.instancia = ControladorSocket.INSTANCIA_GERADA++;
        this.conexao = conexaoSocket;
        this.socketHook = hook;
        this.usarThreadsExtras = threadsExtras;
        try {
            if (servidor) {
                this.streamOut = new ObjectOutputStream(this.conexao.getOutputStream());
                this.streamIn = new ObjectInputStream(this.conexao.getInputStream());
            }
            else {
                this.streamIn = new ObjectInputStream(this.conexao.getInputStream());
                this.streamOut = new ObjectOutputStream(this.conexao.getOutputStream());
            }
            this.estaConectado = true;
            new Thread("SimpleSocket - Thread de envios " + this.instancia) {
                @Override
                public void run() {
                    while (!ControladorSocket.this.flag_finalizar) {
                        if (!ControladorSocket.this.filaEnviosPendentes.isEmpty()) {
                            try {
                                ControladorSocket.this.streamOut.writeObject(ControladorSocket.this.filaEnviosPendentes.poll());
                                ControladorSocket.this.streamOut.flush();
                            }
                            catch (IOException ex2) {
                                ControladorSocket.this.filaEnviosPendentes.clear();
                                ControladorSocket.this.estaConectado = false;
                                ControladorSocket.this.terminarConexao(true);
                            }
                        }
                        else {
                            try {
                                Thread.sleep(5L);
                            }
                            catch (InterruptedException ex) {
                                ControladorSocket.this.socketHook.erroFatal(ex);
                            }
                        }
                    }
                }
            }.start();
            new Thread("SimpleSocket - Thread de recep\u00e7\u00e3o " + this.instancia) {
                @Override
                public void run() {
                    while (!ControladorSocket.this.flag_finalizar) {
                        try {
                            final PacoteRede recebido = (PacoteRede)ControladorSocket.this.streamIn.readObject();
                            if (recebido.isResposta()) {
                                recebido.setHorarioRecebido(System.currentTimeMillis());
                                ControladorSocket.this.filaRespostasRecebidas.add(recebido);
                            }
                            else {
                                switch (recebido.getID()) {
                                    case -1: {
                                        ControladorSocket.this.enviarResposta(new PacoteRede(), recebido.getIndice());
                                        continue;
                                    }
                                    case -2: {
                                        ControladorSocket.this.enviarResposta(new PacoteRede(true), recebido.getIndice());
                                        ControladorSocket.this.terminarConexao(false);
                                        if (recebido.getDado() != null && recebido.getDado() instanceof String) {
                                            ControladorSocket.this.socketHook.conexaoTerminada((String)recebido.getDado());
                                            continue;
                                        }
                                        ControladorSocket.this.socketHook.conexaoTerminada("Pedido de encerramento de conex\u00e3o recebido.");
                                        continue;
                                    }
                                    default: {
                                        ControladorSocket.this.pedidoRecebido(recebido);
                                        continue;
                                    }
                                }
                            }
                        }
                        catch (IOException ex) {
                            ControladorSocket.this.estaConectado = false;
                            ControladorSocket.this.terminarConexao(true);
                        }
                        catch (ClassNotFoundException ex2) {}
                        catch (ConexaoEncerrada conexaoEncerrada) {}
                    }
                }
            }.start();
            this.horarioConexaoIniciada = System.currentTimeMillis();
        }
        catch (IOException ex) {
            this.estaConectado = false;
            throw new ConexaoEncerrada("N\u00e3o \u00e9 poss\u00edvel comunicar-se com o cliente/servidor.");
        }
    }
    
    private void pedidoRecebido(final PacoteRede pedido) {
        if (this.usarThreadsExtras) {
            new Thread("SimpleSocket - Thread de pedido " + this.instancia) {
                @Override
                public void run() {
                    ControladorSocket.this.socketHook.pedidoRecebido(pedido);
                }
            }.start();
        }
        else {
            this.socketHook.pedidoRecebido(pedido);
        }
    }
    
    public PacoteRede aguardarResposta(final int indice, final long tempoTimeout) throws RespostaTimeoutException {
        final long primeiroTempo = System.currentTimeMillis();
        while (System.currentTimeMillis() - primeiroTempo <= tempoTimeout) {
            for (final PacoteRede pacote : this.filaRespostasRecebidas) {
                if (pacote.getIndice() == indice && pacote.isResposta()) {
                    this.filaRespostasRecebidas.remove(pacote);
                    return pacote;
                }
            }
            try {
                Thread.sleep(5L);
            }
            catch (InterruptedException ex) {
                this.socketHook.erroFatal(ex);
            }
        }
        throw new RespostaTimeoutException("Tempo esgotado aguardando resposta pela rede.");
    }
    
    public PacoteRede aguardarResposta(final int indice) throws RespostaTimeoutException {
        return this.aguardarResposta(indice, 5000L);
    }
    
    public void enviarResposta(final PacoteRede pacote, final int indice) throws ConexaoEncerrada {
        if (this.flag_encerrando) {
            throw new ConexaoEncerrada("A conex\u00e3o se tornou inativa.");
        }
        if (!pacote.isResposta()) {
            throw new IllegalArgumentException("O dado \u00e0 ser enviado \u00e9 um pedido e n\u00e3o uma resposta.");
        }
        pacote.setIndice(indice);
        this.filaEnviosPendentes.add(pacote);
    }
    
    public int enviarPedido(final PacoteRede dado) throws ConexaoEncerrada {
        if (this.flag_encerrando) {
            throw new ConexaoEncerrada("A conex\u00e3o se tornou inativa.");
        }
        if (dado.isResposta()) {
            throw new IllegalArgumentException("O dado \u00e0 ser enviado \u00e9 uma resposta e n\u00e3o um pedido.");
        }
        final int indice = this.obterIndiceUnico();
        dado.setIndice(indice);
        this.filaEnviosPendentes.add(dado);
        return indice;
    }
    
    public synchronized void terminarConexao(final boolean chamarHook) {
        if (this.metodoTerminarChamado) {
            return;
        }
        this.metodoTerminarChamado = true;
        new Thread("SimpleSocket - Thread de t\u00e9rmino " + this.instancia) {
            @Override
            public void run() {
                if (!ControladorSocket.this.flag_encerrando) {
                    ControladorSocket.this.flag_encerrando = true;
                    if (ControladorSocket.this.estaConectado) {
                        while (!ControladorSocket.this.filaEnviosPendentes.isEmpty()) {
                            try {
                                Thread.sleep(5L);
                            }
                            catch (InterruptedException ex) {}
                        }
                    }
                    ControladorSocket.this.flag_finalizar = true;
                    ControladorSocket.this.filaEnviosPendentes.clear();
                    ControladorSocket.this.filaRespostasRecebidas.clear();
                    try {
                        ControladorSocket.this.streamIn.close();
                    }
                    catch (IOException ex2) {}
                    try {
                        ControladorSocket.this.streamOut.close();
                    }
                    catch (IOException ex3) {}
                    try {
                        ControladorSocket.this.conexao.close();
                    }
                    catch (IOException ex4) {}
                    if (chamarHook) {
                        ControladorSocket.this.socketHook.conexaoTerminada("Conex\u00f5es encerrada pelo cliente.");
                    }
                }
            }
        }.start();
    }
    
    public synchronized void terminarConexao(final String msg, final boolean chamarHook) {
        if (this.metodoTerminarChamado) {
            return;
        }
        this.metodoTerminarChamado = true;
        new Thread("SimpleSocket - Thread de t\u00e9rmino " + this.instancia) {
            @Override
            public void run() {
                if (!ControladorSocket.this.flag_encerrando) {
                    if (ControladorSocket.this.estaConectado) {
                        final PacoteRede pedido = new PacoteRede(-2, "Conex\u00e3o encerrada.");
                        if (msg != null) {
                            pedido.setDado(msg);
                        }
                        try {
                            final int x = ControladorSocket.this.enviarPedido(pedido);
                            ControladorSocket.this.flag_encerrando = true;
                            ControladorSocket.this.aguardarResposta(x);
                        }
                        catch (ConexaoEncerrada conexaoEncerrada) {}
                        catch (RespostaTimeoutException ex) {}
                        while (ControladorSocket.this.estaConectado && !ControladorSocket.this.filaEnviosPendentes.isEmpty()) {
                            try {
                                Thread.sleep(5L);
                            }
                            catch (InterruptedException ex2) {}
                        }
                    }
                    ControladorSocket.this.flag_encerrando = true;
                    ControladorSocket.this.flag_finalizar = true;
                    ControladorSocket.this.filaEnviosPendentes.clear();
                    ControladorSocket.this.filaRespostasRecebidas.clear();
                    ControladorSocket.this.estaConectado = false;
                    try {
                        ControladorSocket.this.streamIn.close();
                    }
                    catch (IOException ex3) {}
                    try {
                        ControladorSocket.this.streamOut.close();
                    }
                    catch (IOException ex4) {}
                    try {
                        ControladorSocket.this.conexao.close();
                    }
                    catch (IOException ex5) {}
                    if (chamarHook) {
                        ControladorSocket.this.socketHook.conexaoTerminada(msg);
                    }
                }
            }
        }.start();
    }
    
    private int obterIndiceUnico() {
        return this.indiceUnico.getAndIncrement();
    }
    
    public ArrayBlockingQueue<PacoteRede> obterFilaRespostasRecebidas() {
        return this.filaRespostasRecebidas;
    }
    
    public long obterHorarioConexaoIniciada() {
        return this.horarioConexaoIniciada;
    }
    
    public boolean conexaoEstaEncerrada() {
        return this.flag_encerrando || this.flag_finalizar;
    }
    
    public String obterIP() {
        return this.conexao.getInetAddress().getHostAddress();
    }
    
    public void renovarPing() {
        final long tempoAnterior = System.currentTimeMillis();
        try {
            this.aguardarResposta(this.enviarPedido(new PacoteRede(-1)));
            this.ping = System.currentTimeMillis() - tempoAnterior;
        }
        catch (ConexaoEncerrada | RespostaTimeoutException conexaoEncerrada) {
            final Exception ex2;
            final Exception ex = ex2;
            this.ping = -1L;
        }
    }
    
    public long obterPing() {
        return this.ping;
    }
    
    public void limparRespostasAntigas(final long milis) {
        this.filaRespostasRecebidas.stream().filter(pacote -> pacote.getHorarioRecebido() + milis >= System.currentTimeMillis()).forEachOrdered(pacote -> this.filaRespostasRecebidas.remove(pacote));
    }
    
    public void finalize() {
        try {
            this.terminarConexao(false);
        }
        finally {
            try {
                super.finalize();
            }
            catch (Throwable t) {}
        }
    }
    
    static {
        ControladorSocket.INSTANCIA_GERADA = 0;
        ControladorSocket.mensagemMostrada = false;
    }
}
